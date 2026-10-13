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
	"sync/atomic"
	"testing"
	"time"
)

type memorySourceFunc func(context.Context) (client.HostMemorySnapshot, error)

func (f memorySourceFunc) SampleHostMemory(ctx context.Context) (client.HostMemorySnapshot, error) {
	return f(ctx)
}
func publisherStore(t *testing.T) (*Store, *time.Time) {
	t.Helper()
	at := fixtureClock()
	s, err := Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-memory-node", func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s, &at
}
func publisherClient(t *testing.T, at *time.Time, handler http.HandlerFunc) *client.Client {
	t.Helper()
	server := httptest.NewTLSServer(handler)
	t.Cleanup(server.Close)
	c, err := client.NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return *at })
	if err != nil {
		t.Fatal(err)
	}
	return c
}
func publishAck(t *testing.T, w http.ResponseWriter, r *http.Request, at time.Time) []byte {
	t.Helper()
	raw, _ := io.ReadAll(r.Body)
	snapshot, err := client.DecodeHostMemoryTelemetry(raw)
	if err != nil {
		t.Error(err)
		w.WriteHeader(422)
		return raw
	}
	json.NewEncoder(w).Encode(map[string]any{"data": map[string]any{"capture_epoch": snapshot.CaptureEpoch, "sequence": snapshot.Sequence, "payload_hash": snapshot.PayloadHash, "received_at": at.Format(client.SourceContractTime)}, "meta": map[string]any{"request_id": "fictional-request"}})
	return raw
}
func TestHostMemoryPublisherDefaultStoppedNativeSampleAndNetworkAreOutsideSqliteThenMinuteDue(t *testing.T) {
	s, at := publisherStore(t)
	ctx := context.Background()
	calls := 0
	original := memoryJournalExample(t, *at)
	c := publisherClient(t, at, func(w http.ResponseWriter, r *http.Request) {
		queryCtx, cancel := context.WithTimeout(ctx, time.Second)
		defer cancel()
		var version []byte
		if err := s.db.QueryRowContext(queryCtx, "SELECT value FROM node_config WHERE key='schema_version'").Scan(&version); err != nil {
			t.Error("network inside SQLite transaction", err)
		}
		publishAck(t, w, r, *at)
	})
	source := memorySourceFunc(func(context.Context) (client.HostMemorySnapshot, error) {
		calls++
		queryCtx, cancel := context.WithTimeout(ctx, time.Second)
		defer cancel()
		var count int
		if err := s.db.QueryRowContext(queryCtx, "SELECT COUNT(*) FROM outbox").Scan(&count); err != nil {
			t.Fatal("sampling inside SQLite transaction", err)
		}
		next := original
		next.Sequence = uint64(calls)
		next.ObservedAt = at.Format(client.SourceContractTime)
		return client.NewHostMemoryTelemetry(next)
	})
	p, err := NewHostMemoryPublisher(s, c, source)
	if err != nil {
		t.Fatal(err)
	}
	if out, err := p.Tick(ctx); err != nil || out != HostMemoryStopped || calls != 0 {
		t.Fatal("default running", err)
	}
	p.Start()
	if out, err := p.Tick(ctx); err != nil || out != HostMemorySent {
		t.Fatal("native first frame", err)
	}
	if out, err := p.Tick(ctx); err != nil || out != HostMemoryNotDue || calls != 1 {
		t.Fatal("duplicate minute", err)
	}
	*at = at.Add(time.Minute)
	if out, err := p.Tick(ctx); err != nil || out != HostMemorySent || calls != 2 {
		t.Fatal("actual minute", err)
	}
	p.Stop()
	if out, err := p.Tick(ctx); err != nil || out != HostMemoryStopped || calls != 2 {
		t.Fatal("stopped sampled", err)
	}
}
func TestHostMemoryPublisherRestartReplaysOriginalPendingBytesAndOldReceivedTime(t *testing.T) {
	s, at := publisherStore(t)
	originalAt := *at
	calls := 0
	sampled := 0
	var bodies [][]byte
	c := publisherClient(t, at, func(w http.ResponseWriter, r *http.Request) {
		calls++
		if calls == 1 {
			raw, _ := io.ReadAll(r.Body)
			bodies = append(bodies, raw)
			w.WriteHeader(503)
			return
		}
		bodies = append(bodies, publishAck(t, w, r, originalAt))
	})
	source := memorySourceFunc(func(context.Context) (client.HostMemorySnapshot, error) {
		sampled++
		return memoryJournalExample(t, *at), nil
	})
	p, _ := NewHostMemoryPublisher(s, c, source)
	p.Start()
	if _, err := p.Tick(context.Background()); !errors.Is(err, client.ErrRetryable) {
		t.Fatal("real native deferred", err)
	}
	pending, err := s.PendingHostMemorySnapshot(context.Background())
	if err != nil || pending == nil {
		t.Fatal("durable pending", err)
	}
	p.Stop()
	*at = at.Add(600 * time.Second)
	next, _ := NewHostMemoryPublisher(s, c, memorySourceFunc(func(context.Context) (client.HostMemorySnapshot, error) {
		t.Fatal("restart invented a new sample before old ACK")
		return client.HostMemorySnapshot{}, nil
	}))
	next.Start()
	defer next.Stop()
	if out, err := next.Tick(context.Background()); err != nil || out != HostMemorySent || sampled != 1 || len(bodies) != 2 || !bytes.Equal(bodies[0], bodies[1]) {
		t.Fatal("frozen native retry", err)
	}
	var encoded []byte
	s.db.QueryRow("SELECT value FROM node_config WHERE key=?", hostMemoryJournalKey).Scan(&encoded)
	if !bytes.Contains(encoded, []byte(originalAt.Format(client.SourceContractTime))) {
		t.Fatal("original received time lost")
	}
}
func TestHostMemoryPublisherAgedNativeRejectionRetiresButPermissionKeepsPending(t *testing.T) {
	for _, status := range []int{422, 403} {
		s, at := publisherStore(t)
		calls := 0
		var original []byte
		c := publisherClient(t, at, func(w http.ResponseWriter, r *http.Request) {
			raw, _ := io.ReadAll(r.Body)
			calls++
			if calls == 1 {
				original = raw
				w.WriteHeader(503)
				return
			}
			if !bytes.Equal(raw, original) {
				t.Error("aged request changed")
			}
			w.WriteHeader(status)
			json.NewEncoder(w).Encode(map[string]any{"error": map[string]any{"code": "validation", "message": "fictional expired"}, "meta": map[string]any{"request_id": "fictional-request"}})
		})
		p, _ := NewHostMemoryPublisher(s, c, memorySourceFunc(func(context.Context) (client.HostMemorySnapshot, error) { return memoryJournalExample(t, *at), nil }))
		p.Start()
		if _, err := p.Tick(context.Background()); !errors.Is(err, client.ErrRetryable) {
			t.Fatal(err)
		}
		*at = at.Add(121 * time.Second)
		out, err := p.Tick(context.Background())
		if status == 422 {
			if err != nil || out != HostMemoryRetired {
				t.Fatal("actual retired", err)
			}
		} else if !errors.Is(err, client.ErrPermission) {
			t.Fatal("permission", err)
		}
		pending, err := s.PendingHostMemorySnapshot(context.Background())
		if err != nil || (status == 403 && pending == nil) || (status == 422 && pending != nil) {
			t.Fatal("unsafe pending disposal", err)
		}
		p.Stop()
	}
}
func TestHostMemoryPublisherSingleflightAndStopCancelLateSampleBeforeAnySqliteWrite(t *testing.T) {
	s, at := publisherStore(t)
	started := make(chan struct{})
	release := make(chan struct{})
	var network atomic.Int64
	c := publisherClient(t, at, func(w http.ResponseWriter, r *http.Request) { network.Add(1); publishAck(t, w, r, *at) })
	p, _ := NewHostMemoryPublisher(s, c, memorySourceFunc(func(context.Context) (client.HostMemorySnapshot, error) {
		close(started)
		<-release
		return memoryJournalExample(t, *at), nil
	}))
	p.Start()
	done := make(chan error, 1)
	go func() { _, err := p.Tick(context.Background()); done <- err }()
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("not started")
	}
	if out, err := p.Tick(context.Background()); err != nil || out != HostMemoryBusy {
		t.Fatal("not singleflight", err)
	}
	p.Stop()
	close(release)
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("late sample", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("stop did not drain")
	}
	var rows int
	s.db.QueryRow("SELECT COUNT(*) FROM node_config WHERE key=?", hostMemoryJournalKey).Scan(&rows)
	if rows != 0 || network.Load() != 0 {
		t.Fatal("late native write or network")
	}
}
