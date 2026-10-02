package outbox

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

func TestV3QuarantineResolutionRebuildsManifestAndResumesBoundedCleanup(t *testing.T) {
	ctx := context.Background()
	now := fixtureClock()
	path := filepath.Join(t.TempDir(), "queue.db")
	s := openFixtureQueue(t, path, func() time.Time { return now })
	defer func() { s.Close() }()
	if err := s.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	ackCurrentClaim(t, s)
	records := []Record{}
	members := []any{}
	for i := 1; i <= 10; i++ {
		id := fmt.Sprintf("%x", i)
		r := fixtureRecord(id)
		r.Payload = []byte(fmt.Sprintf(`{"id":"%s","source_item_id":"fictional-item-%s"}`, id, id))
		records = append(records, r)
	}
	if err := s.SavePage(ctx, "run-1", "token-a", 0, "", "end", records); err != nil {
		t.Fatal(err)
	}
	batch, err := s.NextObservationBatch(ctx, 10, 512<<10)
	if err != nil || len(batch) != 10 {
		t.Fatal("missing page", err)
	}
	if err = s.ApplyObservationAcks(ctx, batch[:9], fixtureAcks(batch[:9], "accepted")); err != nil {
		t.Fatal(err)
	}
	if err = s.QuarantineObservations(ctx, batch[9:]); err != nil {
		t.Fatal(err)
	}
	now = now.Add(8 * 24 * time.Hour)
	if n, e := s.CleanupConfirmedObservations(ctx, "run-1", 10); n != 0 || !errors.Is(e, ErrRetentionHold) {
		t.Fatal("unconfirmed run cleaned", n, e)
	}
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	s = openFixtureQueue(t, path, func() time.Time { return now })
	for _, original := range records {
		got, _, e := s.Record(ctx, original.ID)
		if e != nil || !bytes.Equal(got.Payload, original.Payload) || got.PayloadHash != original.PayloadHash {
			t.Fatal("day-eight evidence lost", e)
		}
		var payload map[string]any
		if e = json.Unmarshal(got.Payload, &payload); e != nil {
			t.Fatal(e)
		}
		members = append(members, map[string]any{"member_id": got.MemberID, "source_item_id": payload["source_item_id"], "observation_id": got.ObservationID, "payload_hash": got.PayloadHash, "replaces_observation_id": nil})
	}
	sort.Slice(members, func(i, j int) bool {
		return members[i].(map[string]any)["member_id"].(string) < members[j].(map[string]any)["member_id"].(string)
	})
	claim := fixtureClaim()
	token := "token-b"
	until := now.Add(time.Hour).UTC().Format("2006-01-02T15:04:05.000000Z")
	claim.ClaimToken = &token
	claim.LeaseUntil = &until
	if err = s.PersistClaim(claim); err != nil {
		t.Fatal(err)
	}
	ackCurrentClaim(t, s)
	encode := func(b map[string]any) []byte {
		raw, e := json.Marshal(map[string]any{"business": b, "attempt": map[string]any{"claim_token": token, "expected_run_revision": nil}})
		if e != nil {
			t.Fatal(e)
		}
		return raw
	}
	partHash, e := client.Hash(client.ManifestPartHash, map[string]any{"run_id": "run-1", "part_no": 0, "members": members})
	if e != nil {
		t.Fatal(e)
	}
	part := map[string]any{"run_id": "run-1", "part_no": 0, "part_hash": partHash, "members": members}
	rootHash, e := client.Hash(client.ManifestHash, map[string]any{"run_id": "run-1", "expected_count": 10, "parts": []any{map[string]any{"part_no": 0, "part_hash": partHash, "member_count": 10}}})
	if e != nil {
		t.Fatal(e)
	}
	result := map[string]any{"run_id": "run-1", "manifest_hash": rootHash, "part_count": 1, "expected_count": 10, "fetch_outcome": "success", "coverage": map[string]any{}}
	resultHash, e := client.Hash(client.ControlBusinessHash, map[string]any{"operation": "result", "business": result})
	if e != nil {
		t.Fatal(e)
	}
	partRaw, e := json.Marshal(part)
	if e != nil {
		t.Fatal(e)
	}
	proof := TerminalSnapshot{RunID: "run-1", DeliveryState: "partial", ManifestHash: rootHash, ResultBusinessHash: resultHash, PartCount: 1, ExpectedCount: 10, AcceptedCount: 9, ExcludedCount: 1, Parts: []json.RawMessage{partRaw}}
	if e = s.ConfirmTerminal(ctx, "run-1", terminalReader{snapshot: proof}); e == nil {
		t.Fatal("unacknowledged controls granted cleanup")
	}
	// Explicit, acknowledged exclusion resolves the isolated member. Its frozen
	// observation remains quarantined and is never rewritten into accepted input.
	seal := map[string]any{"run_id": "run-1", "member_id": batch[9].MemberID, "expected_member_revision": 1, "action": "seal", "reason": "fictional_reviewed_exclusion"}
	for _, step := range []struct {
		kind, key string
		business  map[string]any
	}{{"run_result", "v3-result", result}, {"member_action", "v3-seal", seal}, {"manifest", "v3-manifest", part}} {
		if _, e = s.StageControl(ctx, step.kind, "run-1", step.key, encode(step.business)); e != nil {
			t.Fatal(e)
		}
	}
	for _, kind := range []string{"member_action", "manifest", "run_result"} {
		a, e := s.NextControlAttempt(ctx)
		if e != nil || a == nil || a.Kind != kind {
			t.Fatal("wrong safe control order", kind, e)
		}
		run := "run-1"
		if ok, e := s.ApplyControlReceipt(ctx, *a, client.ControlResult{BusinessHash: a.BusinessHash, RunID: &run, ReceiptRef: "fictional-control-receipt-" + a.ID}); e != nil || !ok {
			t.Fatal(e)
		}
	}
	bad := proof
	bad.AcceptedCount = 10
	bad.ExcludedCount = 0
	if e = s.ConfirmTerminal(ctx, "run-1", terminalReader{snapshot: bad}); e == nil {
		t.Fatal("wrong membership counts confirmed terminal")
	}
	if e = s.ConfirmTerminal(ctx, "run-1", terminalReader{err: errors.New("fictional offline")}); e == nil {
		t.Fatal("offline terminal read granted cleanup")
	}
	if e = s.ConfirmTerminal(ctx, "run-1", terminalReader{snapshot: proof}); e != nil {
		t.Fatal(e)
	}
	var controlsBefore string
	query := "SELECT group_concat(hex(payload)||':'||payload_hash||':'||state||':'||coalesce(receipt_ref,''),'|') FROM (SELECT * FROM outbox WHERE record_kind!='observation' ORDER BY id)"
	if e = s.db.QueryRow(query).Scan(&controlsBefore); e != nil {
		t.Fatal(e)
	}
	now = now.Add(6*24*time.Hour + 23*time.Hour)
	if n, e := s.CleanupConfirmedObservations(ctx, "run-1", 4); n != 0 || !errors.Is(e, ErrRetentionHold) {
		t.Fatal("six-day23-hour cleanup", n, e)
	}
	now = now.Add(time.Hour)
	if n, e := s.CleanupConfirmedObservations(ctx, "run-1", 4); n != 4 || e != nil {
		t.Fatal("first bounded cleanup", n, e)
	}
	if e = s.Close(); e != nil {
		t.Fatal(e)
	}
	s = openFixtureQueue(t, path, func() time.Time { return now })
	r, _, e := s.Run(ctx, "run-1")
	if e != nil || r.Retention == nil || r.Retention.CleanupState != "partial" || r.Retention.VerifiedManifestHash != rootHash {
		t.Fatal("restart lost exact cleanup marker", e)
	}
	for _, want := range []int{4, 1} {
		if n, e := s.CleanupConfirmedObservations(ctx, "run-1", 4); n != want || e != nil {
			t.Fatal("cleanup resume changed batch", n, e)
		}
	}
	if n, e := s.ObservationCount(ctx, "run-1"); n != 1 || e != nil {
		t.Fatal("quarantined observation deleted", n, e)
	}
	original := records[9]
	got, state, e := s.Record(ctx, original.ID)
	if e != nil || state != "quarantine" || !bytes.Equal(got.Payload, original.Payload) {
		t.Fatal("isolated evidence changed", e)
	}
	var controlsAfter string
	if e = s.db.QueryRow(query).Scan(&controlsAfter); e != nil || controlsBefore != controlsAfter {
		t.Fatal("control/manifests/receipts changed", e)
	}
	if rows, e := s.NextObservationBatch(ctx, 10, 512<<10); e != nil || len(rows) != 0 {
		t.Fatal("deleted body retransmitted", e)
	}
	if e = s.SavePage(ctx, "run-1", token, 0, "", "refetch", records); !errors.Is(e, ErrStaleRun) {
		t.Fatal("terminal run refetched", e)
	}
}

func TestV3UnknownSchemaRefusesStartupWithoutChangingQueueBytes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "queue.db")
	s := openFixtureQueue(t, path, fixtureClock)
	if e := s.PersistClaim(fixtureClaim()); e != nil {
		t.Fatal(e)
	}
	if e := s.SavePage(context.Background(), "run-1", "token-a", 0, "", "end", []Record{fixtureRecord("1")}); e != nil {
		t.Fatal(e)
	}
	if _, e := s.db.Exec("UPDATE node_config SET value=? WHERE key='schema_version'", []byte("99")); e != nil {
		t.Fatal(e)
	}
	if e := s.Close(); e != nil {
		t.Fatal(e)
	}
	before, e := os.ReadFile(path)
	if e != nil {
		t.Fatal(e)
	}
	if unexpected, e := Open(path, "node", fixtureClock); !errors.Is(e, ErrLegacyQueue) {
		if unexpected != nil {
			unexpected.Close()
		}
		t.Fatal("unsupported version opened", e)
	}
	after, e := os.ReadFile(path)
	if e != nil || !bytes.Equal(before, after) {
		t.Fatal("refused database bytes changed", e)
	}
	db, e := sql.Open("sqlite", path)
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	var payload []byte
	if e = db.QueryRow("SELECT payload FROM outbox WHERE observation_id='observation-1'").Scan(&payload); e != nil || !bytes.Equal(payload, fixtureRecord("1").Payload) {
		t.Fatal("refusal lost frozen body", e)
	}
}
