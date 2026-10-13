package outbox

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func memoryJournalExample(t *testing.T, at time.Time) client.HostMemorySnapshot {
	t.Helper()
	maximum := uint64(192 << 20)
	s, err := client.NewHostMemoryTelemetry(client.HostMemorySnapshot{SchemaVersion: client.HostMemorySchema, CaptureEpoch: "00000000-0000-4000-8000-000000000001", ProcessIdentity: strings.Repeat("a", 64), CaptureStartedAt: at.Add(-time.Minute).Format(client.SourceContractTime), Sequence: 1, PID: 12, ObservedAt: at.Format(client.SourceContractTime), RSSBytes: 4096, CgroupEpoch: strings.Repeat("b", 64), MaximumBytes: &maximum, OOMEvents: 0, OOMKills: 0})
	if err != nil {
		t.Fatal(err)
	}
	return s
}
func memoryJournalAck(t *testing.T, s client.HostMemorySnapshot, at time.Time) client.HostMemoryAck {
	t.Helper()
	raw, _ := json.Marshal(map[string]any{"capture_epoch": s.CaptureEpoch, "sequence": s.Sequence, "payload_hash": s.PayloadHash, "received_at": at.Format(client.SourceContractTime)})
	v, err := client.DecodeHostMemoryAck(raw, s, at)
	if err != nil {
		t.Fatal(err)
	}
	return v
}
func TestHostMemoryJournalRestartPreservesPendingBytesAndOriginalNodeIdentity(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	path := filepath.Join(t.TempDir(), "queue.db")
	s, err := Open(path, "fictional-memory-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	first, err := s.StageHostMemorySnapshot(ctx, memoryJournalExample(t, at))
	if err != nil {
		t.Fatal(err)
	}
	s.Close()
	at = at.Add(time.Minute)
	s, err = Open(path, "fictional-memory-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	candidate := first.Snapshot
	candidate.Sequence = 2
	candidate.ObservedAt = at.Format(client.SourceContractTime)
	candidate, err = client.NewHostMemoryTelemetry(candidate)
	if err != nil {
		t.Fatal(err)
	}
	replay, err := s.StageHostMemorySnapshot(ctx, candidate)
	if err != nil || replay.Revision != first.Revision || !bytes.Equal(replay.Snapshot.FrozenWireBytes(), first.Snapshot.FrozenWireBytes()) {
		t.Fatal("changed pending", err)
	}
	pending, err := s.PendingHostMemorySnapshot(ctx)
	if err != nil || pending == nil || pending.Revision != 1 {
		t.Fatal("pending native pointer", err)
	}
	var version []byte
	var rows int
	s.db.QueryRow("SELECT value FROM node_config WHERE key='schema_version'").Scan(&version)
	s.db.QueryRow("SELECT COUNT(*) FROM outbox").Scan(&rows)
	if string(version) != "3" || rows != 0 {
		t.Fatal("changed original schema or business queue")
	}
	s.nodeID = "fictional-other-node"
	if _, err = s.PendingHostMemorySnapshot(ctx); err != ErrHostMemoryJournal {
		t.Fatal("wrong node accepted", err)
	}
}
func TestHostMemoryJournalOriginalAckCommitsThenReplaysWithoutRefreshingAndRejectsOtherFrame(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-memory-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	first, err := s.StageHostMemorySnapshot(ctx, memoryJournalExample(t, at))
	if err != nil {
		t.Fatal(err)
	}
	if err = s.ApplyHostMemoryAck(ctx, first, client.HostMemoryAck{}); err != ErrHostMemoryJournal {
		t.Fatal("forged ack")
	}
	ack := memoryJournalAck(t, first.Snapshot, at)
	if err = s.ApplyHostMemoryAck(ctx, first, ack); err != nil {
		t.Fatal(err)
	}
	at = at.Add(600 * time.Second)
	if err = s.ApplyHostMemoryAck(ctx, first, ack); err != nil {
		t.Fatal("retained original", err)
	}
	pending, err := s.PendingHostMemorySnapshot(ctx)
	if err != nil || pending != nil {
		t.Fatal("ack did not retire pending", err)
	}
	var revision uint64
	s.db.QueryRow("SELECT revision FROM node_config WHERE key=?", hostMemoryJournalKey).Scan(&revision)
	if revision != 2 {
		t.Fatal("replay refreshed local time")
	}
	next := first.Snapshot
	next.Sequence = 2
	next.ObservedAt = at.Format(client.SourceContractTime)
	next, err = client.NewHostMemoryTelemetry(next)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.StageHostMemorySnapshot(ctx, next); err != nil {
		t.Fatal(err)
	}
	if err = s.ApplyHostMemoryAck(ctx, first, ack); err != ErrHostMemoryJournal {
		t.Fatal("old frame overwrote later pointer")
	}
}
func memoryNativeRejection(t *testing.T, s client.HostMemorySnapshot, at time.Time, status int) *client.HostMemoryRejected {
	t.Helper()
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(status)
		json.NewEncoder(w).Encode(map[string]any{"error": map[string]any{"code": "validation", "message": "fictional expired sample"}, "meta": map[string]any{"request_id": "fictional-request"}})
	}))
	defer server.Close()
	c, _ := client.NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return at })
	_, err := c.SubmitHostMemorySnapshot(context.Background(), s)
	var rejected *client.HostMemoryRejected
	if !errors.As(err, &rejected) {
		t.Fatal("actual server rejection required", err)
	}
	return rejected
}
func TestHostMemoryJournalOnlyActualRejectedAgedPendingCanRetireWithoutInventingAck(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-memory-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	first, err := s.StageHostMemorySnapshot(ctx, memoryJournalExample(t, at))
	if err != nil {
		t.Fatal(err)
	}
	rejected := memoryNativeRejection(t, first.Snapshot, at, 422)
	if err = s.RetireRejectedHostMemorySnapshot(ctx, first, rejected); err != ErrHostMemoryJournal {
		t.Fatal("fresh rejection silently healed")
	}
	at = at.Add(121 * time.Second)
	if err = s.RetireRejectedHostMemorySnapshot(ctx, first, nil); err != ErrHostMemoryJournal {
		t.Fatal("guessed retirement")
	}
	rejected = memoryNativeRejection(t, first.Snapshot, at, 422)
	if err = s.RetireRejectedHostMemorySnapshot(ctx, first, rejected); err != nil {
		t.Fatal("actual rejected age", err)
	}
	if err = s.RetireRejectedHostMemorySnapshot(ctx, first, rejected); err != nil {
		t.Fatal("retire replay", err)
	}
	pending, err := s.PendingHostMemorySnapshot(ctx)
	if err != nil || pending != nil {
		t.Fatal("expired pointer", err)
	}
	var encoded []byte
	s.db.QueryRow("SELECT value FROM node_config WHERE key=?", hostMemoryJournalKey).Scan(&encoded)
	if !bytes.Contains(encoded, []byte(`"state":"expired"`)) || !bytes.Contains(encoded, []byte(`"ack":null`)) {
		t.Fatal("native evidence lost or invented ack")
	}
	next := first.Snapshot
	next.Sequence = 2
	next.ObservedAt = at.Format(client.SourceContractTime)
	next, err = client.NewHostMemoryTelemetry(next)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.StageHostMemorySnapshot(ctx, next); err != nil {
		t.Fatal("new actual frame", err)
	}
}

func TestHostMemoryJournalDamageCapacityClockOrWrongRejectionCannotRetireOriginal(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-memory-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	first, err := s.StageHostMemorySnapshot(ctx, memoryJournalExample(t, at))
	if err != nil {
		t.Fatal(err)
	}
	var original []byte
	s.db.QueryRow("SELECT value FROM node_config WHERE key=?", hostMemoryJournalKey).Scan(&original)
	for _, raw := range [][]byte{bytes.Replace(original, []byte(first.Snapshot.PayloadHash), []byte(strings.Repeat("c", 64)), 1), bytes.Replace(original, []byte("{"), []byte(`{"unknown":true,`), 1), bytes.Repeat([]byte("x"), 16385)} {
		s.db.Exec("UPDATE node_config SET value=? WHERE key=?", raw, hostMemoryJournalKey)
		if _, err = s.PendingHostMemorySnapshot(ctx); err != ErrHostMemoryJournal {
			t.Fatal("damaged journal", err)
		}
	}
	s.db.Exec("UPDATE node_config SET value=? WHERE key=?", original, hostMemoryJournalKey)
	at = at.Add(-time.Microsecond)
	if _, err = s.PendingHostMemorySnapshot(ctx); err != ErrHostMemoryJournal {
		t.Fatal("clock rollback")
	}
	at = at.Add(121*time.Second + time.Microsecond)
	other := first.Snapshot
	other.Sequence = 2
	other.ObservedAt = at.Format(client.SourceContractTime)
	other, err = client.NewHostMemoryTelemetry(other)
	if err != nil {
		t.Fatal(err)
	}
	rejected := memoryNativeRejection(t, other, at, 422)
	if err = s.RetireRejectedHostMemorySnapshot(ctx, first, rejected); err != ErrHostMemoryJournal {
		t.Fatal("different native request rejection retired original")
	}
	pending, err := s.PendingHostMemorySnapshot(ctx)
	if err != nil || pending == nil || pending.Revision != 1 {
		t.Fatal("original pending lost", err)
	}
}
func TestHostMemoryJournalNativeSqlRollbackAndConflictPreserveExactPending(t *testing.T) {
	ctx := context.Background()
	at := fixtureClock()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-memory-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	first, err := s.StageHostMemorySnapshot(ctx, memoryJournalExample(t, at))
	if err != nil {
		t.Fatal(err)
	}
	ack := memoryJournalAck(t, first.Snapshot, at)
	_, err = s.db.Exec("CREATE TRIGGER fictional_memory_fail BEFORE UPDATE ON node_config WHEN NEW.key='collector-host-memory-journal-v1' BEGIN SELECT RAISE(ABORT,'fictional rollback'); END")
	if err != nil {
		t.Fatal(err)
	}
	if err = s.ApplyHostMemoryAck(ctx, first, ack); err == nil {
		t.Fatal("native trigger should fail")
	}
	pending, err := s.PendingHostMemorySnapshot(ctx)
	if err != nil || pending == nil || pending.Revision != 1 || !bytes.Equal(pending.Snapshot.FrozenWireBytes(), first.Snapshot.FrozenWireBytes()) {
		t.Fatal("failed commit erased pending", err)
	}
	s.db.Exec("DROP TRIGGER fictional_memory_fail")
	if err = s.ApplyHostMemoryAck(ctx, first, ack); err != nil {
		t.Fatal(err)
	}
	at = at.Add(time.Minute)
	bad := first.Snapshot
	bad.Sequence = 2
	bad.ObservedAt = at.Format(client.SourceContractTime)
	bad.ProcessIdentity = strings.Repeat("c", 64)
	bad, err = client.NewHostMemoryTelemetry(bad)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.StageHostMemorySnapshot(ctx, bad); err != ErrHostMemoryJournal {
		t.Fatal("changed same capture identity")
	}
	bad = first.Snapshot
	bad.CaptureEpoch = "00000000-0000-4000-8000-000000000002"
	bad.Sequence = 1
	bad.CaptureStartedAt = first.Snapshot.ObservedAt
	bad.ObservedAt = at.Format(client.SourceContractTime)
	bad, err = client.NewHostMemoryTelemetry(bad)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.StageHostMemorySnapshot(ctx, bad); err != ErrHostMemoryJournal {
		t.Fatal("invented overlapping capture")
	}
}
