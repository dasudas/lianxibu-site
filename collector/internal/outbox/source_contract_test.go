package outbox

import (
	"bytes"
	"context"
	"database/sql"
	"path/filepath"
	"testing"
	"time"
)

func TestSourceContractJournalRestartAndPendingOriginal(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	path := filepath.Join(t.TempDir(), "queue.db")
	clock := func() time.Time { return at }
	s, err := Open(path, "fictional-node", clock)
	if err != nil {
		t.Fatal(err)
	}
	first, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil || first.Snapshot.TotalFailures != 0 || first.Snapshot.Events == nil {
		t.Fatal("complete cold native zero", err)
	}
	if err = s.CaptureSourceContractFailure(ctx, "fictional-source", 1); err != nil {
		t.Fatal(err)
	}
	at = at.Add(time.Second)
	if err = s.CaptureSourceContractFailure(ctx, "fictional-source", 1); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s, err = Open(path, "fictional-node", clock)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	replay, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil || replay.RecordID != first.RecordID || !bytes.Equal(replay.Snapshot.FrozenWireBytes(), first.Snapshot.FrozenWireBytes()) {
		t.Fatal("pending bytes changed", err)
	}
	var state []byte
	if err = s.db.QueryRow("SELECT value FROM node_config WHERE key=?", sourceJournalKey("fictional-source", 1)).Scan(&state); err != nil || !bytes.Contains(state, []byte(`"total_failures":2`)) {
		t.Fatal("native failures lost", err)
	}
	next, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 2)
	if err != nil || next.Snapshot.Epoch == first.Snapshot.Epoch || next.Snapshot.TotalFailures != 0 {
		t.Fatal("config namespace", err)
	}
	other, err := s.StageSourceContractSnapshot(ctx, "fictional-other", 1)
	if err != nil || other.RecordID == first.RecordID {
		t.Fatal("source isolation", err)
	}
	var version []byte
	s.db.QueryRow("SELECT value FROM node_config WHERE key='schema_version'").Scan(&version)
	if string(version) != "3" {
		t.Fatal("changed schema")
	}
}
func TestSourceContractJournalWindowCapacityRollback(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for i := 0; i < 200; i++ {
		if err = s.CaptureSourceContractFailure(ctx, "fictional-source", 1); err != nil {
			t.Fatal(i, err)
		}
	}
	if err = s.CaptureSourceContractFailure(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("must not truncate events")
	}
	snapshot, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil || snapshot.Snapshot.TotalFailures != 200 || len(snapshot.Snapshot.Events) != 200 {
		t.Fatal("capacity rollback", err)
	}
	at = at.Add(600 * time.Second)
	if err = s.CaptureSourceContractFailure(ctx, "fictional-source", 1); err != nil {
		t.Fatal("exclusive old window pruning", err)
	}
	j := sourceContractJournal{}
	err = s.write(ctx, func(conn *sql.Conn) error {
		var e error
		j, e = s.sourceJournal(ctx, conn, "fictional-source", 1, at)
		return e
	})
	if err != nil || j.TotalFailures != 201 || len(j.Events) != 1 {
		t.Fatal("monotonic lifetime total", err)
	}
}
func TestSourceContractJournalRejectsCorruptClockAndNode(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	path := filepath.Join(t.TempDir(), "queue.db")
	s, err := Open(path, "fictional-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	first, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil {
		t.Fatal(err)
	}
	at = at.Add(-time.Microsecond)
	if _, err = s.StageSourceContractSnapshot(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("clock rollback")
	}
	at = at.Add(time.Microsecond)
	if _, err = s.db.Exec("UPDATE outbox SET payload_hash='fictional-corrupt' WHERE id=?", first.RecordID); err != nil {
		t.Fatal(err)
	}
	if _, err = s.StageSourceContractSnapshot(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("corrupt native row")
	}
	if _, err = s.db.Exec("UPDATE node_config SET value=? WHERE key=?", []byte(`{"schema_version":"source-contract-journal-v1"}`), sourceJournalKey("fictional-source", 1)); err != nil {
		t.Fatal(err)
	}
	if err = s.CaptureSourceContractFailure(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("missing journal fields")
	}
	if _, err = s.StageSourceContractSnapshot(ctx, "fictional-other", 1); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s, err = Open(path, "fictional-other-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if _, err = s.StageSourceContractSnapshot(ctx, "fictional-other", 1); err != ErrSourceContractJournal {
		t.Fatal("node replacement cannot report zero")
	}
}
func TestSourceContractJournalFailedCommitKeepsOriginal(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err = s.CaptureSourceContractFailure(ctx, "fictional-source", 1); err != nil {
		t.Fatal(err)
	}
	_, err = s.db.Exec(`CREATE TRIGGER fictional_journal_failure BEFORE UPDATE ON node_config WHEN NEW.key LIKE 'source-contract:%' BEGIN SELECT RAISE(ABORT,'fictional disk failure'); END`)
	if err != nil {
		t.Fatal(err)
	}
	if err = s.CaptureSourceContractFailure(ctx, "fictional-source", 1); err == nil {
		t.Fatal("failed commit must stop")
	}
	if _, err = s.StageSourceContractSnapshot(ctx, "fictional-source", 1); err == nil {
		t.Fatal("failed freeze must rollback")
	}
	var count int
	s.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind=?", SourceContractRecordKind).Scan(&count)
	if count != 0 {
		t.Fatal("snapshot escaped transaction")
	}
	_, err = s.db.Exec("DROP TRIGGER fictional_journal_failure")
	if err != nil {
		t.Fatal(err)
	}
	first, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil || first.Snapshot.TotalFailures != 1 {
		t.Fatal("failed capture changed original fact", err)
	}
}
