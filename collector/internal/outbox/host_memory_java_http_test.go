package outbox

import (
	"bytes"
	"context"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"
)

type memoryInteropHTTP struct {
	drop          bool
	calls, status int
	body          []byte
}

func (h *memoryInteropHTTP) RoundTrip(r *http.Request) (*http.Response, error) {
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		return nil, err
	}
	r.Body = io.NopCloser(bytes.NewReader(raw))
	h.body = append([]byte(nil), raw...)
	response, err := (submissionHTTP{}).RoundTrip(r)
	if err != nil {
		return nil, err
	}
	h.calls++
	h.status = response.StatusCode
	if h.drop && response.StatusCode == 200 {
		h.drop = false
		io.Copy(io.Discard, response.Body)
		response.Body.Close()
		return nil, errors.New("fictional committed ACK loss")
	}
	return response, nil
}

// Real Go client/journal/publisher and native Java HTTP; the frame is explicitly fictional, not Linux qualification.
func TestHostMemoryJavaHTTP(t *testing.T) {
	origin := os.Getenv("VHM_BASE_URL")
	if origin == "" {
		t.Skip("actual Java host memory fixture is not active")
	}
	u, err := url.Parse(origin)
	if err != nil || u.Scheme != "https" || u.Hostname() != "127.0.0.1" || u.Port() == "" {
		t.Fatal("fictional loopback required")
	}
	at, err := time.Parse(client.SourceContractTime, os.Getenv("VHM_NOW"))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	store, err := Open(os.Getenv("VHM_QUEUE"), os.Getenv("VHM_NODE"), func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	mode := os.Getenv("VHM_MODE")
	transport := &memoryInteropHTTP{drop: mode == "prepare"}
	api, err := client.NewClient(origin, os.Getenv("VHM_TOKEN"), &http.Client{Transport: transport, Timeout: 10 * time.Second}, func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	var original client.HostMemorySnapshot
	if mode == "prepare" {
		maximum := uint64(192 << 20)
		original, err = client.NewHostMemoryTelemetry(client.HostMemorySnapshot{SchemaVersion: client.HostMemorySchema, CaptureEpoch: "00000000-0000-4000-8000-000000000001", ProcessIdentity: strings.Repeat("a", 64), CaptureStartedAt: at.Add(-time.Minute).Format(client.SourceContractTime), Sequence: 1, PID: 12, ObservedAt: at.Format(client.SourceContractTime), RSSBytes: 192 << 20, CgroupEpoch: strings.Repeat("b", 64), MaximumBytes: &maximum})
	} else {
		var raw []byte
		raw, err = os.ReadFile(os.Getenv("VHM_FROZEN"))
		if err == nil {
			original, err = client.DecodeHostMemoryTelemetry(raw)
		}
	}
	if err != nil {
		t.Fatal("original fictional frame unavailable", err)
	}
	samples := 0
	source := memorySourceFunc(func(context.Context) (client.HostMemorySnapshot, error) {
		samples++
		next := original
		if mode != "prepare" {
			next.Sequence++
			if mode == "revoked" {
				next.Sequence++
			}
			next.ObservedAt = at.Format(client.SourceContractTime)
			next.RSSBytes = 100 << 20
		}
		return client.NewHostMemoryTelemetry(next)
	})
	publisher, err := NewHostMemoryPublisher(store, api, source)
	if err != nil {
		t.Fatal(err)
	}
	publisher.Start()
	defer publisher.Stop()
	if mode == "prepare" {
		_, err = publisher.Tick(ctx)
		pending, e := store.PendingHostMemorySnapshot(ctx)
		if err == nil || e != nil || pending == nil || transport.status != 200 || samples != 1 {
			t.Fatal("committed ACK loss must keep original native journal pending", err, e)
		}
		if !bytes.Equal(pending.Snapshot.FrozenWireBytes(), original.FrozenWireBytes()) {
			t.Fatal("original payload changed")
		}
		if err = os.WriteFile(os.Getenv("VHM_FROZEN"), original.FrozenWireBytes(), 0600); err != nil {
			t.Fatal(err)
		}
		return
	}
	if mode == "replay" {
		outcome, err := publisher.Tick(ctx)
		if err != nil || outcome != HostMemorySent || samples != 0 || !bytes.Equal(transport.body, original.FrozenWireBytes()) {
			t.Fatal("restart must replay only original bytes and retained ACK", err)
		}
		if pending, err := store.PendingHostMemorySnapshot(ctx); err != nil || pending != nil {
			t.Fatal("exact original ACK not persisted", err)
		}
		outcome, err = publisher.Tick(ctx)
		if err != nil || outcome != HostMemorySent || samples != 1 || transport.calls != 2 {
			t.Fatal("next native journal frame not delivered", err)
		}
		return
	}
	if mode != "revoked" {
		t.Fatal("closed fictional mode required")
	}
	_, err = publisher.Tick(ctx)
	if !errors.Is(err, client.ErrPermission) || transport.status != 403 || samples != 1 {
		t.Fatal("revoked authority must reject current pending frame", err)
	}
	if pending, err := store.PendingHostMemorySnapshot(ctx); err != nil || pending == nil {
		t.Fatal("permission failure retired original frame", err)
	}
}
