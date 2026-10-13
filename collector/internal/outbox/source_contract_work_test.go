package outbox

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func TestSourceContractWorkInterruptedRestartIsUnknown(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	path := filepath.Join(t.TempDir(), "queue.db")
	clock := func() time.Time { return at }
	s, err := Open(path, "fictional-node", clock)
	if err != nil {
		t.Fatal(err)
	}
	token, err := s.BeginSourceContractWork(ctx, "fictional-source", 1)
	if err != nil || token == "" {
		t.Fatal(err)
	}
	if _, err = s.StageSourceContractSnapshot(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("incomplete parser must not become zero")
	}
	s.Close()
	s, err = Open(path, "fictional-node", clock)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if _, err = s.BeginSourceContractWork(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("restart cannot overwrite unknown work")
	}
	if _, err = s.SourceContractSnapshotDue(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("unknown work cannot publish prior healthy snapshot")
	}
	if err = s.CompleteSourceContractWork(ctx, "fictional-source", 1, "fictional-wrong", false); err != ErrSourceContractJournal {
		t.Fatal("original token")
	}
	if err = s.CompleteSourceContractWork(ctx, "fictional-source", 1, token, true); err != nil {
		t.Fatal(err)
	}
	q, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil || q.Snapshot.TotalFailures != 1 {
		t.Fatal("native original completion", err)
	}
}
func TestSourceContractWorkFailedCompletionRemainsUnknown(t *testing.T) {
	ctx := context.Background()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	token, err := s.BeginSourceContractWork(ctx, "fictional-source", 1)
	if err != nil {
		t.Fatal(err)
	}
	s.db.Exec(`CREATE TRIGGER fictional_work_failure BEFORE UPDATE ON node_config BEGIN SELECT RAISE(ABORT,'fictional commit failure'); END`)
	if err = s.CompleteSourceContractWork(ctx, "fictional-source", 1, token, true); err == nil {
		t.Fatal("failed journal commit")
	}
	s.db.Exec("DROP TRIGGER fictional_work_failure")
	if _, err = s.StageSourceContractSnapshot(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("failed capture cannot publish healthy")
	}
	if err = s.CompleteSourceContractWork(ctx, "fictional-source", 1, token, true); err != nil {
		t.Fatal(err)
	}
	q, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil || q.Snapshot.TotalFailures != 1 || len(q.Snapshot.Events) != 1 {
		t.Fatal("completion retry must count once", err)
	}
	if err = s.CompleteSourceContractWork(ctx, "fictional-source", 1, token, true); err != ErrSourceContractJournal {
		t.Fatal("double original completion")
	}
}
func TestSourceContractWorkKnownNetworkCompletionAndNewConfig(t *testing.T) {
	ctx := context.Background()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	token, err := s.BeginSourceContractWork(ctx, "fictional-source", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err = s.CompleteSourceContractWork(ctx, "fictional-source", 1, token, false); err != nil {
		t.Fatal(err)
	}
	q, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil || q.Snapshot.TotalFailures != 0 {
		t.Fatal("known network is not parser error", err)
	}
	token, err = s.BeginSourceContractWork(ctx, "fictional-source", 1)
	if err != nil {
		t.Fatal(err)
	}
	next, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 2)
	if err != nil || next.Snapshot.TotalFailures != 0 || next.Snapshot.Epoch == q.Snapshot.Epoch {
		t.Fatal("new reviewed configuration namespace", err)
	}
	if _, err = s.StageSourceContractSnapshot(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("old unknown proof remains unknown")
	}
}
