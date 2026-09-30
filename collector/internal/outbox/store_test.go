package outbox

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

func fixtureClock() time.Time { return time.Date(2026, 9, 30, 8, 0, 0, 0, time.UTC) }

func fixtureClaim() client.RunClaim {
	claim := client.RunClaim{}
	claim.Run.RunID = "run-1"
	claim.Run.SourceID = "source"
	claim.Run.ConfigVersion = 1
	claim.Run.WindowStart = "2026-09-29T00:00:00.000000Z"
	claim.Run.WindowEnd = "2026-09-30T00:00:00.000000Z"
	claim.Config.SourceID = "source"
	claim.Config.ConfigVersion = 1
	token, until := "token-a", "2026-09-30T09:00:00.000000Z"
	claim.ClaimToken = &token
	claim.LeaseUntil = &until
	claim.CurrentLeaseValid = true
	return claim
}

func fixtureRecord(id string) Record {
	return Record{ID: id, Kind: "observation", RecordKey: strings.Repeat(id, 64), SourceID: "source", RunID: "run-1",
		MemberID: "member-" + id, ObservationID: "observation-" + id, Payload: []byte(`{"id":"` + id + `"}`),
		PayloadHash: strings.Repeat("a", 64), ProtocolRevision: "0.11", HashVersion: 1}
}

func TestSchemaV3ClaimAckAndDurablePageAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "queue.db")
	store, err := Open(path, "node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	var version []byte
	if err := store.db.QueryRow("SELECT value FROM node_config WHERE key='schema_version'").Scan(&version); err != nil || string(version) != "3" {
		t.Fatal("schema version missing", err)
	}
	for pragma, want := range map[string]int{"foreign_keys": 1, "journal_mode": 0, "synchronous": 2} {
		if pragma == "journal_mode" {
			var mode string
			store.db.QueryRow("PRAGMA journal_mode").Scan(&mode)
			if mode != "wal" {
				t.Fatal("WAL disabled")
			}
			continue
		}
		var got int
		store.db.QueryRow("PRAGMA " + pragma).Scan(&got)
		if got != want {
			t.Fatal("unsafe pragma", pragma, got)
		}
	}
	claim := fixtureClaim()
	if err := store.PersistClaim(claim); err != nil {
		t.Fatal(err)
	}
	if err := store.PersistClaim(claim); err != nil {
		t.Fatal("claim replay", err)
	}
	var business, attempts int
	store.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind='lease_action'").Scan(&business)
	store.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind='control_attempt'").Scan(&attempts)
	if business != 1 || attempts != 1 {
		t.Fatal("ACK replay duplicated action", business, attempts)
	}
	var businessID, attemptRef, key string
	store.db.QueryRow("SELECT id FROM outbox WHERE record_kind='lease_action'").Scan(&businessID)
	store.db.QueryRow("SELECT business_ref,idempotency_key FROM outbox WHERE record_kind='control_attempt'").Scan(&attemptRef, &key)
	if businessID != attemptRef || key != businessID {
		t.Fatal("control business/attempt identity mismatch")
	}
	first, second := fixtureRecord("1"), fixtureRecord("2")
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "page-2", []Record{first, second}); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(path, "node", fixtureClock)
	if err != nil {
		t.Fatal("reopen WAL queue", err)
	}
	defer store.Close()
	state, revision, err := store.Run(context.Background(), "run-1")
	if err != nil || revision != 1 || state.Cursor != "page-2" {
		t.Fatal("checkpoint lost", err)
	}
	for _, original := range []Record{first, second} {
		got, _, err := store.Record(context.Background(), original.ID)
		if err != nil || got.ObservationID != original.ObservationID || got.PayloadHash != original.PayloadHash || !bytes.Equal(got.Payload, original.Payload) {
			t.Fatal("observation identity changed", err)
		}
	}
}

func TestFailedPageTransactionNeverAdvancesCheckpointOrLeavesOneObservation(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	first := fixtureRecord("1")
	broken := fixtureRecord("2")
	broken.PayloadHash = "bad"
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "page-2", []Record{first, broken}); !errors.Is(err, ErrConflict) {
		t.Fatal("invalid member committed", err)
	}
	state, revision, err := store.Run(context.Background(), "run-1")
	if err != nil || revision != 0 || state.Cursor != "" {
		t.Fatal("checkpoint moved after rollback", err)
	}
	if _, _, err := store.Record(context.Background(), first.ID); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal("partial page committed")
	}
	if err := store.SavePage(context.Background(), "run-1", "token-wrong", 0, "", "page-2", []Record{first}); !errors.Is(err, ErrStaleRun) {
		t.Fatal("stale token moved checkpoint")
	}
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "page-2", []Record{first}); err != nil {
		t.Fatal(err)
	}
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "page-3", []Record{first}); !errors.Is(err, ErrStaleRun) {
		t.Fatal("old cursor replayed")
	}
}

func TestSendingRecoveryKeepsOriginalBytesAndLegacyIdentityIsNotMerged(t *testing.T) {
	path := filepath.Join(t.TempDir(), "queue.db")
	store, err := Open(path, "node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	record := fixtureRecord("1")
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "end", []Record{record}); err != nil {
		t.Fatal(err)
	}
	store.db.Exec("UPDATE outbox SET state='sending' WHERE id=?", record.ID)
	store.Close()
	store, err = Open(path, "node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Recover(context.Background()); err != nil {
		t.Fatal(err)
	}
	got, state, err := store.Record(context.Background(), record.ID)
	if err != nil || state != "pending" || !bytes.Equal(got.Payload, record.Payload) || got.ObservationID != record.ObservationID {
		t.Fatal("recovery changed frozen observation", err)
	}
	store.db.Exec("UPDATE node_config SET value='1' WHERE key='schema_version'")
	store.Close()
	if _, err := Open(path, "node", fixtureClock); !errors.Is(err, ErrLegacyQueue) {
		t.Fatal("legacy identity was merged", err)
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var count int
	db.QueryRow("SELECT COUNT(*) FROM outbox WHERE observation_id=?", record.ObservationID).Scan(&count)
	if count != 1 {
		t.Fatal("legacy rejection deleted observation")
	}
}

func TestNewLeaseSupersedesOldAckWithoutRewritingFrozenObservation(t *testing.T) {
	now := fixtureClock()
	store, err := Open(filepath.Join(t.TempDir(), "queue.db"), "node", func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	first := fixtureClaim()
	if err := store.PersistClaim(first); err != nil {
		t.Fatal(err)
	}
	record := fixtureRecord("1")
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "next", []Record{record}); err != nil {
		t.Fatal(err)
	}
	now = time.Date(2026, 9, 30, 9, 1, 0, 0, time.UTC)
	second := fixtureClaim()
	second.Run.RunRevision = 2
	token, until := "token-b", "2026-09-30T10:00:00.000000Z"
	second.ClaimToken = &token
	second.LeaseUntil = &until
	if _, err := client.PrepareLeaseAction("ack", second, "new-ack", now); err != nil {
		t.Fatal("new claim invalid before storage", err)
	}
	if err := store.PersistClaim(second); err != nil {
		t.Fatal("new lease rejected", err)
	}
	var oldActions, newActions int
	store.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind='lease_action' AND state='superseded'").Scan(&oldActions)
	store.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind='lease_action' AND state='pending'").Scan(&newActions)
	if oldActions != 1 || newActions != 1 {
		t.Fatal("old ACK identity migrated to new token")
	}
	if err := store.SavePage(context.Background(), "run-1", "token-a", 1, "next", "final", nil); !errors.Is(err, ErrStaleRun) {
		t.Fatal("old token advanced new lease")
	}
	if err := store.SavePage(context.Background(), "run-1", "token-b", 1, "next", "final", nil); err != nil {
		t.Fatal(err)
	}
	got, _, err := store.Record(context.Background(), record.ID)
	if err != nil || got.ObservationID != record.ObservationID || !bytes.Equal(got.Payload, record.Payload) {
		t.Fatal("lease change rewrote observation", err)
	}
}
