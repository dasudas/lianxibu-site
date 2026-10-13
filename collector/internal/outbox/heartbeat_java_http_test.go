package outbox

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/telemetry"
	"io"
	"net/http"
	"net/url"
	"os"
	"testing"
	"time"
)

type heartbeatInteropHTTP struct {
	status int
	calls  int
	body   []byte
}

func (h *heartbeatInteropHTTP) RoundTrip(r *http.Request) (*http.Response, error) {
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		return nil, err
	}
	r.Body = io.NopCloser(bytes.NewReader(raw))
	h.body = append([]byte(nil), raw...)
	response, err := (submissionHTTP{}).RoundTrip(r)
	if err == nil {
		h.status = response.StatusCode
		h.calls++
	}
	return response, err
}
func TestHeartbeatJavaHTTP(t *testing.T) {
	origin := os.Getenv("VHB_BASE_URL")
	if origin == "" {
		t.Skip("actual Java heartbeat fixture is not active")
	}
	u, err := url.Parse(origin)
	if err != nil || u.Scheme != "https" || u.Hostname() != "127.0.0.1" || u.Port() == "" {
		t.Fatal("fictional loopback required")
	}
	at, err := time.Parse("2006-01-02T15:04:05.000000Z", os.Getenv("VHB_NOW"))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	clock := func() time.Time { return at }
	path := os.Getenv("VHB_QUEUE")
	store, err := Open(path, os.Getenv("VHB_NODE"), clock)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	transport := &heartbeatInteropHTTP{}
	sender, err := telemetry.NewHTTPSender(origin, os.Getenv("VHB_TOKEN"), &http.Client{Transport: transport, Timeout: 10 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	mode := os.Getenv("VHB_MODE")
	if mode == "prepare" || mode == "replay" {
		sampler, err := telemetry.NewSQLiteSampler(path)
		if err != nil {
			t.Fatal(err)
		}
		defer sampler.Close()
		if mode == "replay" {
			raw, err := os.ReadFile(os.Getenv("VHB_FROZEN"))
			if err != nil {
				t.Fatal(err)
			}
			var original telemetry.Heartbeat
			if json.Unmarshal(raw, &original) != nil {
				t.Fatal("original closed heartbeat missing")
			}
			if err = sender.SendHeartbeat(ctx, original); err != nil || transport.status != 200 {
				t.Fatal("old original heartbeat replay rejected", err)
			}
		}
		monitor, err := telemetry.NewMonitor(sampler, sender, clock, telemetry.DefaultLimits())
		if err != nil {
			t.Fatal(err)
		}
		state, err := monitor.Tick(ctx)
		if err != nil || !state.SampleValid || state.TelemetryUnknown || state.Sample.OutboxBytes == 0 || state.Sample.FreeBytes == 0 || state.Sample.RSSBytes == 0 || state.Sample.PendingObservations != 0 || state.Sample.PendingControls != 0 || state.Sample.OldestPendingAt != nil || transport.status != 200 {
			t.Fatal("actual native SQLite/host sample did not reach Java", err)
		}
		if mode == "prepare" {
			if err = os.WriteFile(os.Getenv("VHB_FROZEN"), transport.body, 0600); err != nil {
				t.Fatal(err)
			}
		}
		calls := transport.calls
		if err = sampler.Close(); err != nil {
			t.Fatal(err)
		}
		unknown, err := monitor.Tick(ctx)
		if err == nil || unknown.SampleValid || !unknown.TelemetryUnknown || transport.calls != calls {
			t.Fatal("native failed SQL sampling sent invented healthy heartbeat")
		}
		return
	}
	if mode != "revoked" {
		t.Fatal("closed fictional phase required")
	}
	raw, err := os.ReadFile(os.Getenv("VHB_FROZEN"))
	if err != nil {
		t.Fatal(err)
	}
	var original telemetry.Heartbeat
	if json.Unmarshal(raw, &original) != nil {
		t.Fatal("original heartbeat missing")
	}
	if err = sender.SendHeartbeat(ctx, original); err == nil || transport.status != 403 {
		t.Fatal("revoked original heartbeat accepted")
	}
}
