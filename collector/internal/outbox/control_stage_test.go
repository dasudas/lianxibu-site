package outbox

import (
	"context"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

func manifestWire(t *testing.T, token string) []byte {
	t.Helper()
	members := []any{map[string]any{"member_id": "member-1", "source_item_id": nil, "observation_id": "observation-1",
		"payload_hash": strings.Repeat("a", 64), "replaces_observation_id": nil}}
	part := map[string]any{"run_id": "run-1", "part_no": 0, "members": members}
	hash, err := client.Hash(client.ManifestPartHash, part)
	if err != nil {
		t.Fatal(err)
	}
	business := map[string]any{"run_id": "run-1", "part_no": 0, "part_hash": hash, "members": members}
	wire, err := json.Marshal(map[string]any{"business": business, "attempt": map[string]any{"claim_token": token, "expected_run_revision": nil}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.DecodeManifestControl(wire, "run-1", "node"); err != nil {
		t.Fatal(err)
	}
	return wire
}

func ackCurrentClaim(t *testing.T, store *Store) {
	t.Helper()
	a, err := store.NextControlAttempt(context.Background())
	if err != nil || a == nil {
		t.Fatal("missing claim ack", err)
	}
	runID := "run-1"
	var lease int64
	if err := store.db.QueryRow("SELECT lease_until FROM source_state WHERE state_key=? AND record_kind='run'", runID).Scan(&lease); err != nil {
		t.Fatal(err)
	}
	until := time.UnixMicro(lease).UTC().Format("2006-01-02T15:04:05.000000Z")
	result := client.ControlResult{BusinessHash: a.BusinessHash, RunID: &runID, ReceiptRef: "ack-receipt", CurrentLeaseValid: true, LeaseUntil: &until}
	if applied, err := store.ApplyControlReceipt(context.Background(), *a, result); err != nil || !applied {
		t.Fatal("claim ACK not committed", err)
	}
}

func TestManifestBusinessIsFrozenAndNewLeaseGetsNewAttemptOnly(t *testing.T) {
	now := fixtureClock()
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	wire := manifestWire(t, "token-a")
	if _, err := store.StageControl(context.Background(), "manifest", "run-1", "manifest-key", wire); err == nil {
		t.Fatal("manifest staged before claim ACK")
	}
	ackCurrentClaim(t, store)
	check, _ := client.DecodeManifestControl(wire, "run-1", "node")
	bh, ah, _ := client.DecodeFrozenAttempt(wire, "node", "manifest")
	if check.BusinessHash != bh || check.AttemptHash != ah {
		t.Fatalf("hash mismatch manifest=%t attempt=%t", check.BusinessHash == bh, check.AttemptHash == ah)
	}
	first, err := store.StageControl(context.Background(), "manifest", "run-1", "manifest-key", wire)
	if err != nil {
		t.Fatal(err)
	}
	replay, err := store.StageControl(context.Background(), "manifest", "run-1", "manifest-key", wire)
	if err != nil || replay.ID != first.ID || replay.BusinessID != first.BusinessID {
		t.Fatal("same attempt did not replay", err)
	}
	if _, err := store.StageControl(context.Background(), "manifest", "run-1", "different-key", wire); err == nil {
		t.Fatal("parallel attempt allowed")
	}
	old, err := store.NextControlAttempt(context.Background())
	if err != nil || old == nil || old.ID != first.ID {
		t.Fatal("manifest attempt not selected", err)
	}
	if err := store.StopControlAttempt(context.Background(), *old, "stale_claim"); err != nil {
		t.Fatal(err)
	}
	if _, err := store.StageControl(context.Background(), "manifest", "run-1", "manifest-key", wire); err == nil {
		t.Fatal("superseded attempt was revived")
	}
	now = time.Date(2026, 9, 30, 9, 1, 0, 0, time.UTC)
	claim := fixtureClaim()
	token, until := "token-b", "2026-09-30T10:00:00.000000Z"
	claim.ClaimToken = &token
	claim.LeaseUntil = &until
	claim.Run.RunRevision = 2
	if err := store.PersistClaim(claim); err != nil {
		t.Fatal(err)
	}
	ackCurrentClaim(t, store)
	second, err := store.StageControl(context.Background(), "manifest", "run-1", "manifest-key-new", manifestWire(t, "token-b"))
	if err != nil || second.BusinessID != first.BusinessID || second.ID == first.ID || second.BusinessHash != first.BusinessHash {
		t.Fatal("new token did not preserve business", err)
	}
	var supersedes string
	if err := store.db.QueryRow("SELECT supersedes_attempt_id FROM outbox WHERE id=?", second.ID).Scan(&supersedes); err != nil || supersedes != first.ID {
		t.Fatal("attempt chain lost", err)
	}
	runID := "run-1"
	if applied, err := store.ApplyControlReceipt(context.Background(), *old, client.ControlResult{BusinessHash: old.BusinessHash, RunID: &runID, ReceiptRef: "old"}); err != nil || applied {
		t.Fatal("old manifest response confirmed new attempt", err)
	}
	current, err := store.NextControlAttempt(context.Background())
	if err != nil || current == nil || current.ID != second.ID {
		t.Fatal("new attempt not pending", err)
	}
	if applied, err := store.ApplyControlReceipt(context.Background(), *current, client.ControlResult{BusinessHash: current.BusinessHash, RunID: &runID, ReceiptRef: "new"}); err != nil || !applied {
		t.Fatal("new manifest not confirmed", err)
	}
}

func TestResultAndMemberSealFreezeDistinctBusiness(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", func() time.Time { return fixtureClock() })
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	ackCurrentClaim(t, store)
	encode := func(business map[string]any) []byte {
		t.Helper()
		wire, err := json.Marshal(map[string]any{"business": business,
			"attempt": map[string]any{"claim_token": "token-a", "expected_run_revision": nil}})
		if err != nil {
			t.Fatal(err)
		}
		return wire
	}
	resultWire := encode(map[string]any{"run_id": "run-1", "manifest_hash": strings.Repeat("a", 64),
		"part_count": 0, "expected_count": 0, "fetch_outcome": "success", "coverage": map[string]any{}})
	result, err := store.StageControl(context.Background(), "run_result", "run-1", "result-key", resultWire)
	if err != nil {
		t.Fatal(err)
	}
	if result.Kind != "run_result" || result.BusinessHash == "" {
		t.Fatal("result was not frozen")
	}
	firstWire := encode(map[string]any{"run_id": "run-1", "member_id": "member-1", "expected_member_revision": 1,
		"action": "seal", "reason": "non_retryable_rejection"})
	first, err := store.StageControl(context.Background(), "member_action", "run-1", "seal-1", firstWire)
	if err != nil {
		t.Fatal(err)
	}
	secondWire := encode(map[string]any{"run_id": "run-1", "member_id": "member-1", "expected_member_revision": 2,
		"action": "seal", "reason": "non_retryable_rejection"})
	second, err := store.StageControl(context.Background(), "member_action", "run-1", "seal-2", secondWire)
	if err != nil || second.BusinessID == first.BusinessID || second.BusinessHash == first.BusinessHash {
		t.Fatal("distinct seal business was collapsed", err)
	}
}
