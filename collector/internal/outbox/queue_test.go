package outbox

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

func TestObservationAckAppliesOnlyExactFrozenBatchAtomically(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "end", []Record{fixtureRecord("1"), fixtureRecord("2")}); err != nil {
		t.Fatal(err)
	}
	batch, err := store.NextObservationBatch(context.Background(), 10, 512<<10)
	if err != nil || len(batch) != 2 {
		t.Fatal("batch not selected", err)
	}
	if err := store.ApplyObservationAcks(context.Background(), batch, []client.ObservationAck{{ObservationID: batch[0].ObservationID, MemberID: batch[0].MemberID, Status: "accepted"}}); !errors.Is(err, ErrConflict) {
		t.Fatal("partial ack applied", err)
	}
	for _, item := range batch {
		_, state, err := store.Record(context.Background(), item.RecordID)
		if err != nil || state != "sending" {
			t.Fatal("partial ACK changed row")
		}
	}
	ref, at := "receipt", "2026-09-30T08:00:00.000000Z"
	acks := []client.ObservationAck{
		{ObservationID: batch[0].ObservationID, MemberID: batch[0].MemberID, Status: "accepted", ReceiptRef: &ref, AcceptedAt: &at},
		{ObservationID: batch[1].ObservationID, MemberID: batch[1].MemberID, Status: "blocked"},
	}
	if err := store.ApplyObservationAcks(context.Background(), batch, acks); err != nil {
		t.Fatal(err)
	}
	_, first, _ := store.Record(context.Background(), batch[0].RecordID)
	_, second, _ := store.Record(context.Background(), batch[1].RecordID)
	if first != "acknowledged" || second != "quarantine" {
		t.Fatal("wrong durable ACK disposition", first, second)
	}
	var blob []byte
	if err := store.db.QueryRow("SELECT payload FROM outbox WHERE id=?", batch[0].RecordID).Scan(&blob); err != nil || string(blob) != string(batch[0].Wire) {
		t.Fatal("ACK overwrote frozen payload", err)
	}
}

func TestIdentityConflictQuarantinesExactFrozenObservation(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	record := fixtureRecord("3")
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "end", []Record{record}); err != nil {
		t.Fatal(err)
	}
	batch, err := store.NextObservationBatch(context.Background(), 10, 512<<10)
	if err != nil || len(batch) != 1 {
		t.Fatal("frozen observation not selected", err)
	}
	if err := store.QuarantineObservations(context.Background(), batch); err != nil {
		t.Fatal(err)
	}
	got, state, err := store.Record(context.Background(), record.ID)
	if err != nil || state != "quarantine" || string(got.Payload) != string(record.Payload) {
		t.Fatal("identity conflict lost frozen evidence", err)
	}
	var code string
	if err := store.db.QueryRow("SELECT last_error_code FROM outbox WHERE id=?", record.ID).Scan(&code); err != nil || code != "identity_conflict" {
		t.Fatal("identity conflict reason not durable", err)
	}
	if err := store.QuarantineObservations(context.Background(), batch); !errors.Is(err, ErrConflict) {
		t.Fatal("same identity quarantined twice", err)
	}
}

func TestControlReceiptBindsAttemptAndLateOldClaimCannotAcknowledgeNewBusiness(t *testing.T) {
	now := fixtureClock()
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	old, err := store.NextControlAttempt(context.Background())
	if err != nil || old == nil || old.Kind != "lease_action" {
		t.Fatal("ACK attempt not queued", err)
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
	runID := "run-1"
	oldResult := client.ControlResult{BusinessHash: old.BusinessHash, RunID: &runID, ReceiptRef: "old-receipt"}
	applied, err := store.ApplyControlReceipt(context.Background(), *old, oldResult)
	if err != nil || applied {
		t.Fatal("old attempt acknowledged after lease change", err)
	}
	current, err := store.NextControlAttempt(context.Background())
	if err != nil || current == nil || current.ID == old.ID || current.BusinessID == old.BusinessID {
		t.Fatal("new ACK reused old identity", err)
	}
	newResult := client.ControlResult{BusinessHash: current.BusinessHash, RunID: &runID, ReceiptRef: "new-receipt", CurrentLeaseValid: true, LeaseUntil: &until}
	applied, err = store.ApplyControlReceipt(context.Background(), *current, newResult)
	if err != nil || !applied {
		t.Fatal("current attempt not confirmed", err)
	}
	_, state, err := store.Record(context.Background(), current.BusinessID)
	if err != nil || state != "acknowledged" {
		t.Fatal("business not confirmed with attempt", err)
	}
}

func TestRetryKeepsObservationAndControlWireBytes(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	record := fixtureRecord("1")
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "end", []Record{record}); err != nil {
		t.Fatal(err)
	}
	batch, err := store.NextObservationBatch(context.Background(), 10, 512<<10)
	if err != nil || len(batch) != 1 {
		t.Fatal(err)
	}
	if err := store.RetryObservations(context.Background(), batch); err != nil {
		t.Fatal(err)
	}
	got, state, err := store.Record(context.Background(), record.ID)
	if err != nil || state != "pending" || string(got.Payload) != string(record.Payload) {
		t.Fatal("retry changed observation", err)
	}
	attempt, err := store.NextControlAttempt(context.Background())
	if err != nil || attempt == nil {
		t.Fatal(err)
	}
	if err := store.RetryControlAttempt(context.Background(), *attempt); err != nil {
		t.Fatal(err)
	}
	_, state, err = store.Record(context.Background(), attempt.ID)
	if err != nil || state != "pending" {
		t.Fatal("control retry lost attempt", err)
	}
}
