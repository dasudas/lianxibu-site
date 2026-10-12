package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func memoryExample() []byte {
	return []byte(`{"schema_version":"collector-host-memory-v1","capture_epoch":"00000000-0000-4000-8000-000000000001","process_identity":"` + strings.Repeat("a", 64) + `","capture_started_at":"2026-01-01T00:09:00.000000Z","sequence":1,"pid":12,"observed_at":"2026-01-01T00:10:00.000000Z","rss_bytes":1048576,"cgroup_epoch":"` + strings.Repeat("b", 64) + `","maximum_bytes":201326592,"oom_events":0,"oom_kills":2}`)
}
func memoryFixture(t *testing.T) (HostMemorySnapshot, map[string]any, time.Time) {
	t.Helper()
	s, err := DecodeHostMemoryTelemetry(memoryExample())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 1, 1, 0, 11, 0, 0, time.UTC)
	return s, map[string]any{"capture_epoch": s.CaptureEpoch, "sequence": s.Sequence, "payload_hash": s.PayloadHash, "received_at": now.Format(SourceContractTime)}, now
}
func TestHostMemoryClosedIdentityAndIndependentNativeValues(t *testing.T) {
	s, _, _ := memoryFixture(t)
	again, err := NewHostMemoryTelemetry(s)
	if err != nil || !bytes.Equal(s.FrozenWireBytes(), again.FrozenWireBytes()) || s.OOMKills != 2 {
		t.Fatal("native canonical identity", err)
	}
	raw := s.FrozenWireBytes()
	raw[0] = '!'
	if s.FrozenWireBytes()[0] != '{' || s.String() != "HostMemorySnapshot[redacted]" {
		t.Fatal("mutable alias or diagnostic")
	}
	s.MaximumBytes = nil
	s.RSSBytes = 256 << 20
	fresh, err := NewHostMemoryTelemetry(s)
	if err != nil || fresh.MaximumBytes != nil || fresh.PayloadHash == s.PayloadHash {
		t.Fatal("native unlimited or RSS fact", err)
	}
}
func TestHostMemoryInvalidClosedSnapshot(t *testing.T) {
	original := string(memoryExample())
	bad := []string{original + "{}", strings.Replace(original, "{", `{"sequence":1,`, 1), strings.Replace(original, "{", `{"healthy":true,`, 1), strings.Replace(original, `"pid":12`, `"pid":0`, 1), strings.Replace(original, `"sequence":1`, `"sequence":0`, 1), strings.Replace(original, `"rss_bytes":1048576`, `"rss_bytes":0`, 1), strings.Replace(original, `"maximum_bytes":201326592`, `"maximum_bytes":0`, 1), strings.Replace(original, "00:09:00.000000Z", "00:11:00.000000Z", 1), strings.Replace(original, "00:10:00.000000Z", "00:10:00Z", 1)}
	for _, key := range []string{"sequence", "pid", "rss_bytes", "maximum_bytes", "oom_events", "oom_kills"} {
		var m map[string]json.RawMessage
		json.Unmarshal(memoryExample(), &m)
		for _, value := range []string{"-1", "1.0", "9007199254740992", `"1"`} {
			m[key] = json.RawMessage(value)
			raw, _ := json.Marshal(m)
			bad = append(bad, string(raw))
		}
		delete(m, key)
		raw, _ := json.Marshal(m)
		bad = append(bad, string(raw))
	}
	for _, raw := range bad {
		if _, err := DecodeHostMemoryTelemetry([]byte(raw)); err != ErrHostMemoryTelemetry {
			t.Fatal("must reject", raw, err)
		}
	}
	for _, raw := range [][]byte{nil, bytes.Repeat([]byte(" "), 8193)} {
		if _, err := DecodeHostMemoryTelemetry(raw); err != ErrHostMemoryTelemetry {
			t.Fatal("raw cap")
		}
	}
}
func TestHostMemoryOriginalFourFieldAckClosedAndBound(t *testing.T) {
	changes := []func(map[string]any){func(m map[string]any) { m["healthy"] = true }, func(m map[string]any) { delete(m, "sequence") }, func(m map[string]any) { m["sequence"] = 2 }, func(m map[string]any) { m["capture_epoch"] = "00000000-0000-4000-8000-000000000002" }, func(m map[string]any) { m["payload_hash"] = strings.Repeat("c", 64) }, func(m map[string]any) { m["received_at"] = "2026-01-01T00:11:00.000001Z" }, func(m map[string]any) { m["received_at"] = "2026-01-01T00:09:00.000000Z" }, func(m map[string]any) { m["received_at"] = "2026-01-01T00:11:00Z" }}
	for _, change := range changes {
		s, m, now := memoryFixture(t)
		change(m)
		raw, _ := json.Marshal(m)
		if _, err := DecodeHostMemoryAck(raw, s, now); err != ErrHostMemoryAck {
			t.Fatal("bad ack accepted", err)
		}
	}
	s, m, now := memoryFixture(t)
	raw, _ := json.Marshal(m)
	s.Sequence = 99
	s.PayloadHash = "changed"
	a, err := DecodeHostMemoryAck(raw, s, now.Add(time.Hour))
	if err != nil || a.Sequence != 1 || a.String() != "HostMemoryAck[redacted]" {
		t.Fatal("original retained ack", err)
	}
	frozen := a.FrozenWireBytes()
	frozen[0] = '!'
	if a.FrozenWireBytes()[0] != '{' {
		t.Fatal("ack alias")
	}
}
func TestHostMemoryActualHTTPSFrozenRetryAndClosedEnvelope(t *testing.T) {
	s, ack, now := memoryFixture(t)
	original := s.FrozenWireBytes()
	calls := 0
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		raw, _ := io.ReadAll(r.Body)
		if r.Method != "POST" || r.URL.Path != "/api/collector/v1/host-memory" || r.URL.RawQuery != "" || r.Header.Get("Idempotency-Key") != "" || r.Header.Get("Authorization") != "Bearer "+strings.Repeat("f", 43) || !bytes.Equal(raw, original) {
			t.Error("original bytes/route/authority")
		}
		if calls == 1 {
			w.WriteHeader(503)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"data": ack, "meta": map[string]any{"request_id": "fictional-request"}})
	}))
	defer server.Close()
	c, err := NewClient(server.URL, strings.Repeat("f", 43), server.Client(), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	s.CaptureEpoch = "changed"
	s.Sequence = 99
	if _, err = c.SubmitHostMemorySnapshot(context.Background(), s); !errors.Is(err, ErrRetryable) {
		t.Fatal("expected native retry", err)
	}
	a, err := c.SubmitHostMemorySnapshot(context.Background(), s)
	if err != nil || a.Sequence != 1 || calls != 2 {
		t.Fatal("original ACK", err)
	}
	for _, raw := range [][]byte{[]byte(`{"data":{},"meta":{}}`), []byte(`{"data":{},"data":{},"meta":{}}`), bytes.Repeat([]byte(" "), 16385)} {
		bad := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(raw) }))
		c, _ := NewClient(bad.URL, "fictional-token", bad.Client(), func() time.Time { return now })
		_, err := c.SubmitHostMemorySnapshot(context.Background(), s)
		bad.Close()
		if err != ErrHostMemoryAck {
			t.Fatal("bad envelope", err)
		}
	}
}
