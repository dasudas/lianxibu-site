package outbox

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

func journalAck(t *testing.T, q QueuedSourceContractSnapshot, at time.Time) client.SourceContractAck {
	t.Helper()
	s := q.Snapshot
	raw, _ := json.Marshal(map[string]any{"schema_version": client.SourceContractAckSchema, "source_id": s.SourceID, "config_version": s.ConfigVersion, "epoch": s.Epoch, "sequence": s.Sequence, "payload_hash": s.PayloadHash, "received_at": at.Format(client.SourceContractTime)})
	ack, err := client.DecodeSourceContractAck(raw, s, at)
	if err != nil {
		t.Fatal(err)
	}
	return ack
}
func TestSourceContractAckStorePermanentReplayAndFreshNext(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	q, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil {
		t.Fatal(err)
	}
	ack := journalAck(t, q, at)
	if err = s.CaptureSourceContractFailure(ctx, "fictional-source", 1); err != nil {
		t.Fatal(err)
	}
	if err = s.ApplySourceContractAck(ctx, q, ack); err != nil {
		t.Fatal(err)
	}
	if due, err := s.SourceContractSnapshotDue(ctx, "fictional-source", 1); err != nil || due {
		t.Fatal("minute cadence", err)
	}
	at = at.Add(time.Minute)
	next, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil || next.Snapshot.Sequence != 2 || next.Snapshot.TotalFailures != 1 || len(next.Snapshot.Events) != 1 {
		t.Fatal("new snapshot retained later native failure", err)
	}
	if err = s.ApplySourceContractAck(ctx, q, ack); err != nil {
		t.Fatal("permanent original replay before newer pending", err)
	}
	again, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil || again.RecordID != next.RecordID {
		t.Fatal("old ACK consumed newer pointer", err)
	}
	var count int
	s.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind=?", SourceContractRecordKind).Scan(&count)
	if count != 2 {
		t.Fatal("permanent records")
	}
}
func TestSourceContractAckStoreFailedLocalCommitAndHTTPSRetry(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	path := filepath.Join(t.TempDir(), "queue.db")
	s, err := Open(path, "fictional-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer func() { s.Close() }()
	var bodies [][]byte
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		bodies = append(bodies, raw)
		snapshot, e := client.DecodeSourceContractTelemetry(raw)
		if e != nil {
			t.Error(e)
			w.WriteHeader(400)
			return
		}
		q := QueuedSourceContractSnapshot{Snapshot: snapshot}
		ack := journalAck(t, q, at)
		var data any
		json.Unmarshal(ack.FrozenWireBytes(), &data)
		json.NewEncoder(w).Encode(map[string]any{"data": data, "meta": map[string]any{"request_id": "fictional-request"}})
	}))
	defer server.Close()
	api, err := client.NewClient(server.URL, "fictional-node-token", server.Client(), func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	q, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil {
		t.Fatal(err)
	}
	_, err = s.db.Exec(`CREATE TRIGGER fictional_ack_commit_failure BEFORE UPDATE ON node_config WHEN NEW.key LIKE 'source-contract:%' BEGIN SELECT RAISE(ABORT,'fictional commit failure'); END`)
	if err != nil {
		t.Fatal(err)
	}
	if err = s.PublishSourceContractSnapshot(ctx, api, "fictional-source", 1); err == nil {
		t.Fatal("unknown local commit cannot count delivered")
	}
	var state string
	s.db.QueryRow("SELECT state FROM outbox WHERE id=?", q.RecordID).Scan(&state)
	if state != "pending" {
		t.Fatal("ACK escaped rollback")
	}
	s.db.Exec("DROP TRIGGER fictional_ack_commit_failure")
	s.Close()
	s, err = Open(path, "fictional-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	if err = s.PublishSourceContractSnapshot(ctx, api, "fictional-source", 1); err != nil {
		t.Fatal(err)
	}
	if len(bodies) != 2 || !bytes.Equal(bodies[0], bodies[1]) || !bytes.Equal(bodies[0], q.Snapshot.FrozenWireBytes()) {
		t.Fatal("original immutable retry")
	}
	if err = s.PublishSourceContractSnapshot(ctx, api, "fictional-source", 1); err != nil || len(bodies) != 2 {
		t.Fatal("cadence did not avoid second healthy send", err)
	}
	at = at.Add(time.Minute)
	if err = s.PublishSourceContractSnapshot(ctx, api, "fictional-source", 1); err != nil || len(bodies) != 3 {
		t.Fatal("native next minute", err)
	}
}
func TestSourceContractAckStoreRejectsMismatchedAndCorruptProof(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	q, err := s.StageSourceContractSnapshot(ctx, "fictional-source", 1)
	if err != nil {
		t.Fatal(err)
	}
	other, err := s.StageSourceContractSnapshot(ctx, "fictional-other", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err = s.ApplySourceContractAck(ctx, q, journalAck(t, other, at)); err != ErrSourceContractJournal {
		t.Fatal("foreign ACK")
	}
	bad := q
	bad.Revision++
	if err = s.ApplySourceContractAck(ctx, bad, journalAck(t, q, at)); err != ErrSourceContractJournal {
		t.Fatal("queue CAS")
	}
	ack := journalAck(t, q, at)
	if err = s.ApplySourceContractAck(ctx, q, ack); err != nil {
		t.Fatal(err)
	}
	s.db.Exec("UPDATE outbox SET ack_blob=? WHERE id=?", []byte(`{"received_at":"fictional"}`), q.RecordID)
	if _, err = s.SourceContractSnapshotDue(ctx, "fictional-source", 1); err != ErrSourceContractJournal {
		t.Fatal("bad previous native ACK")
	}
	if err = s.ApplySourceContractAck(ctx, q, ack); err != ErrSourceContractJournal {
		t.Fatal("corrupt permanent replay")
	}
	cancelled, cancel := context.WithCancel(ctx)
	cancel()
	if err = s.PublishSourceContractSnapshot(cancelled, nil, "fictional-source", 1); !errors.Is(err, ErrSourceContractJournal) {
		t.Fatal("missing actual API")
	}
}
