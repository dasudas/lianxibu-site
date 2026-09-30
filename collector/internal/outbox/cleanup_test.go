package outbox

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

type terminalReader struct {
	snapshot TerminalSnapshot
	err      error
}

func (r terminalReader) ReadTerminal(context.Context, string) (TerminalSnapshot, error) {
	return r.snapshot, r.err
}

func terminalFixture(t *testing.T) (*Store, *time.Time, TerminalSnapshot) {
	t.Helper()
	now := fixtureClock()
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	ackCurrentClaim(t, store)
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "end", []Record{fixtureRecord("1")}); err != nil {
		t.Fatal(err)
	}
	batch, err := store.NextObservationBatch(context.Background(), 10, 512<<10)
	if err != nil || len(batch) != 1 {
		t.Fatal("missing observation", err)
	}
	ref, at := "obs-receipt", "2026-09-30T08:00:00.000000Z"
	if err := store.ApplyObservationAcks(context.Background(), batch, []client.ObservationAck{{ObservationID: "observation-1", MemberID: "member-1", Status: "accepted", ReceiptRef: &ref, AcceptedAt: &at}}); err != nil {
		t.Fatal(err)
	}
	wire := manifestWire(t, "token-a")
	parsed, err := client.ParseStrict(wire)
	if err != nil {
		t.Fatal(err)
	}
	part := parsed.(map[string]any)["business"].(map[string]any)
	partRaw, err := json.Marshal(part)
	if err != nil {
		t.Fatal(err)
	}
	manifestHash, err := client.Hash(client.ManifestHash, map[string]any{"run_id": "run-1", "expected_count": 1,
		"parts": []any{map[string]any{"part_no": 0, "part_hash": part["part_hash"], "member_count": 1}}})
	if err != nil {
		t.Fatal(err)
	}
	resultBusiness := map[string]any{"run_id": "run-1", "manifest_hash": manifestHash, "part_count": 1, "expected_count": 1,
		"fetch_outcome": "success", "coverage": map[string]any{}}
	resultHash, err := client.Hash(client.ControlBusinessHash, map[string]any{"operation": "result", "business": resultBusiness})
	if err != nil {
		t.Fatal(err)
	}
	resultWire, err := json.Marshal(map[string]any{"business": resultBusiness, "attempt": map[string]any{"claim_token": "token-a", "expected_run_revision": nil}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.StageControl(context.Background(), "run_result", "run-1", "result-key", resultWire); err != nil {
		t.Fatal(err)
	}
	if _, err := store.StageControl(context.Background(), "manifest", "run-1", "manifest-key", wire); err != nil {
		t.Fatal(err)
	}
	for _, kind := range []string{"manifest", "run_result"} {
		a, err := store.NextControlAttempt(context.Background())
		if err != nil || a == nil || a.Kind != kind {
			t.Fatal("wrong control order", kind, err)
		}
		runID := "run-1"
		if ok, err := store.ApplyControlReceipt(context.Background(), *a, client.ControlResult{BusinessHash: a.BusinessHash, RunID: &runID, ReceiptRef: "control-receipt"}); err != nil || !ok {
			t.Fatal(err)
		}
	}
	snapshot := TerminalSnapshot{RunID: "run-1", DeliveryState: "complete", ManifestHash: manifestHash, ResultBusinessHash: resultHash,
		PartCount: 1, ExpectedCount: 1, AcceptedCount: 1, Parts: []json.RawMessage{partRaw}}
	return store, &now, snapshot
}

func TestTerminalProofAndSevenDayBoundedCleanup(t *testing.T) {
	store, clock, snapshot := terminalFixture(t)
	defer store.Close()
	if err := store.ConfirmTerminal(context.Background(), "run-1", terminalReader{err: errors.New("offline")}); err == nil {
		t.Fatal("offline reader confirmed terminal")
	}
	bad := snapshot
	bad.ManifestHash = strings.Repeat("b", 64)
	if err := store.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: bad}); err == nil {
		t.Fatal("wrong hash confirmed terminal")
	}
	if err := store.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: snapshot}); err != nil {
		t.Fatal(err)
	}
	if n, err := store.CleanupConfirmedObservations(context.Background(), "run-1", 100); !errors.Is(err, ErrRetentionHold) || n != 0 {
		t.Fatal("early cleanup", n, err)
	}
	*clock = clock.Add(7*24*time.Hour - time.Microsecond)
	if n, err := store.CleanupConfirmedObservations(context.Background(), "run-1", 100); !errors.Is(err, ErrRetentionHold) || n != 0 {
		t.Fatal("sub-seven-day cleanup", n, err)
	}
	*clock = clock.Add(time.Microsecond)
	if n, err := store.CleanupConfirmedObservations(context.Background(), "run-1", 101); err == nil || n != 0 {
		t.Fatal("unbounded cleanup", n, err)
	}
	if n, err := store.CleanupConfirmedObservations(context.Background(), "run-1", 1); err != nil || n != 1 {
		t.Fatal("eligible observation not deleted", n, err)
	}
	var observations, manifests, results int
	store.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind='observation'").Scan(&observations)
	store.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind='manifest'").Scan(&manifests)
	store.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind='run_result'").Scan(&results)
	if observations != 0 || manifests != 1 || results != 1 {
		t.Fatal("cleanup deleted protected evidence", observations, manifests, results)
	}
	*clock = clock.Add(-time.Minute)
	if _, err := store.CleanupConfirmedObservations(context.Background(), "run-1", 1); !errors.Is(err, ErrClockRollback) {
		t.Fatal("clock rollback ignored", err)
	}
}

func TestPendingObservationPreventsTerminalMarker(t *testing.T) {
	store, _, snapshot := terminalFixture(t)
	defer store.Close()
	if _, err := store.db.Exec("UPDATE outbox SET state='pending' WHERE record_kind='observation'"); err != nil {
		t.Fatal(err)
	}
	if err := store.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: snapshot}); !errors.Is(err, ErrRetentionHold) {
		t.Fatal("pending observation accepted", err)
	}
	var raw []byte
	store.db.QueryRow("SELECT payload FROM source_state WHERE record_kind='run'").Scan(&raw)
	var state RunState
	if json.Unmarshal(raw, &state) != nil || state.Retention != nil {
		t.Fatal("failed proof persisted retention")
	}
}

func TestUnresolvedQuarantineAndStorageFailureKeepRows(t *testing.T) {
	store, clock, snapshot := terminalFixture(t)
	defer store.Close()
	blocked, _ := json.Marshal(client.ObservationAck{ObservationID: "observation-1", MemberID: "member-1", Status: "blocked"})
	if _, err := store.db.Exec("UPDATE outbox SET state='quarantine',receipt_ref=NULL,ack_blob=? WHERE record_kind='observation'", blocked); err != nil {
		t.Fatal(err)
	}
	snapshot.AcceptedCount = 0
	snapshot.ExcludedCount = 1
	if err := store.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: snapshot}); !errors.Is(err, ErrRetentionHold) {
		t.Fatal("unresolved quarantine confirmed", err)
	}
	rejectedCode := "invalid_identity"
	rejected, _ := json.Marshal(client.ObservationAck{ObservationID: "observation-1", MemberID: "member-1", Status: "rejected", Code: &rejectedCode})
	if _, err := store.db.Exec("UPDATE outbox SET ack_blob=? WHERE record_kind='observation'", rejected); err != nil {
		t.Fatal(err)
	}
	if err := store.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: snapshot}); err != nil {
		t.Fatal(err)
	}
	*clock = clock.Add(7 * 24 * time.Hour)
	if n, err := store.CleanupConfirmedObservations(context.Background(), "run-1", 100); err != nil || n != 0 {
		t.Fatal("quarantine was cleaned", n, err)
	}
	var remaining int
	store.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind='observation'").Scan(&remaining)
	if remaining != 1 {
		t.Fatal("quarantined payload lost")
	}

	store2, clock2, snapshot2 := terminalFixture(t)
	defer store2.Close()
	if err := store2.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: snapshot2}); err != nil {
		t.Fatal(err)
	}
	*clock2 = clock2.Add(7 * 24 * time.Hour)
	if _, err := store2.db.Exec("PRAGMA query_only=ON"); err != nil {
		t.Fatal(err)
	}
	if n, err := store2.CleanupConfirmedObservations(context.Background(), "run-1", 100); err == nil || n != 0 {
		t.Fatal("unwritable storage reported cleanup", n, err)
	}
	if _, err := store2.db.Exec("PRAGMA query_only=OFF"); err != nil {
		t.Fatal(err)
	}
	store2.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind='observation'").Scan(&remaining)
	if remaining != 1 {
		t.Fatal("failed transaction deleted observation")
	}
}

func TestReplacementChainExplainsOldObservationWithoutDeletingIt(t *testing.T) {
	now := fixtureClock()
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	ackCurrentClaim(t, store)
	old := fixtureRecord("0")
	old.MemberID = "member-1"
	current := fixtureRecord("1")
	current.ReplacesObservationID = "observation-0"
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "end", []Record{old, current}); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec("UPDATE outbox SET state='superseded' WHERE observation_id='observation-0'"); err != nil {
		t.Fatal(err)
	}
	batch, err := store.NextObservationBatch(context.Background(), 10, 512<<10)
	if err != nil || len(batch) != 1 || batch[0].ObservationID != "observation-1" {
		t.Fatal("wrong current observation", err)
	}
	ref, at := "receipt", "2026-09-30T08:00:00.000000Z"
	if err := store.ApplyObservationAcks(context.Background(), batch, []client.ObservationAck{{ObservationID: "observation-1", MemberID: "member-1", Status: "accepted", ReceiptRef: &ref, AcceptedAt: &at}}); err != nil {
		t.Fatal(err)
	}
	members := []any{map[string]any{"member_id": "member-1", "source_item_id": nil, "observation_id": "observation-1",
		"payload_hash": strings.Repeat("a", 64), "replaces_observation_id": "observation-0"}}
	partHash, err := client.Hash(client.ManifestPartHash, map[string]any{"run_id": "run-1", "part_no": 0, "members": members})
	if err != nil {
		t.Fatal(err)
	}
	part := map[string]any{"run_id": "run-1", "part_no": 0, "part_hash": partHash, "members": members}
	manifestHash, err := client.Hash(client.ManifestHash, map[string]any{"run_id": "run-1", "expected_count": 1,
		"parts": []any{map[string]any{"part_no": 0, "part_hash": partHash, "member_count": 1}}})
	if err != nil {
		t.Fatal(err)
	}
	result := map[string]any{"run_id": "run-1", "manifest_hash": manifestHash, "part_count": 1, "expected_count": 1,
		"fetch_outcome": "success", "coverage": map[string]any{}}
	resultHash, err := client.Hash(client.ControlBusinessHash, map[string]any{"operation": "result", "business": result})
	if err != nil {
		t.Fatal(err)
	}
	encode := func(b map[string]any) []byte {
		raw, err := json.Marshal(map[string]any{"business": b, "attempt": map[string]any{"claim_token": "token-a", "expected_run_revision": nil}})
		if err != nil {
			t.Fatal(err)
		}
		return raw
	}
	if _, err := store.StageControl(context.Background(), "manifest", "run-1", "manifest-key", encode(part)); err != nil {
		t.Fatal(err)
	}
	if _, err := store.StageControl(context.Background(), "run_result", "run-1", "result-key", encode(result)); err != nil {
		t.Fatal(err)
	}
	for range 2 {
		a, err := store.NextControlAttempt(context.Background())
		if err != nil || a == nil {
			t.Fatal(err)
		}
		runID := "run-1"
		if ok, err := store.ApplyControlReceipt(context.Background(), *a, client.ControlResult{BusinessHash: a.BusinessHash, RunID: &runID, ReceiptRef: "receipt"}); err != nil || !ok {
			t.Fatal(err)
		}
	}
	partRaw, _ := json.Marshal(part)
	snapshot := TerminalSnapshot{RunID: "run-1", DeliveryState: "complete", ManifestHash: manifestHash, ResultBusinessHash: resultHash,
		PartCount: 1, ExpectedCount: 1, AcceptedCount: 1, Parts: []json.RawMessage{partRaw}}
	if err := store.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: snapshot}); err != nil {
		t.Fatal(err)
	}
	now = now.Add(7 * 24 * time.Hour)
	if n, err := store.CleanupConfirmedObservations(context.Background(), "run-1", 100); err != nil || n != 1 {
		t.Fatal(n, err)
	}
	var oldState string
	if err := store.db.QueryRow("SELECT state FROM outbox WHERE observation_id='observation-0'").Scan(&oldState); err != nil || oldState != "superseded" {
		t.Fatal("replacement trace lost", err)
	}
}
