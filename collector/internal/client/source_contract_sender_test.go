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

func sourceContractAckFixture(t *testing.T) (SourceContractSnapshot, map[string]any, time.Time) {
	t.Helper()
	s, err := DecodeSourceContractTelemetry(sourceContractExample(t))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 1, 1, 0, 11, 0, 0, time.UTC)
	return s, map[string]any{"schema_version": SourceContractAckSchema, "source_id": s.SourceID, "config_version": s.ConfigVersion, "epoch": s.Epoch, "sequence": s.Sequence, "payload_hash": s.PayloadHash, "received_at": now.Format(SourceContractTime)}, now
}
func TestSourceContractSenderActualHTTPSAndImmutableRetry(t *testing.T) {
	s, ack, now := sourceContractAckFixture(t)
	original := s.FrozenWireBytes()
	var bodies [][]byte
	var calls int
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		raw, _ := io.ReadAll(r.Body)
		bodies = append(bodies, raw)
		if r.Method != "PUT" || r.URL.Path != "/api/collector/v1/source-contract-telemetry/fictional-source" || r.URL.RawQuery != "" || r.Header.Get("Idempotency-Key") != "" || r.Header.Get("Authorization") != "Bearer "+strings.Repeat("f", 43) {
			t.Error("wire route or authority")
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
	s.SourceID = "fictional-mutation"
	s.PayloadHash = "fictional-hash"
	if _, err = c.SubmitSourceContractSnapshot(context.Background(), s); !errors.Is(err, ErrRetryable) {
		t.Fatal("actual failed request", err)
	}
	receipt, err := c.SubmitSourceContractSnapshot(context.Background(), s)
	if err != nil || receipt.SourceID != "fictional-source" || receipt.Sequence != 1 || receipt.ReceivedAt != now.Format(SourceContractTime) {
		t.Fatal("native ACK", err)
	}
	if calls != 2 || !bytes.Equal(bodies[0], original) || !bytes.Equal(bodies[1], original) {
		t.Fatal("retry changed frozen bytes")
	}
	wire := receipt.FrozenWireBytes()
	wire[0] = '!'
	if receipt.FrozenWireBytes()[0] != '{' {
		t.Fatal("aliased ACK")
	}
}
func TestSourceContractSenderClosedAck(t *testing.T) {
	cases := map[string]func(map[string]any){"unknown": func(m map[string]any) { m["secret"] = "fictional" }, "missing": func(m map[string]any) { delete(m, "epoch") }, "schema": func(m map[string]any) { m["schema_version"] = "old" }, "source": func(m map[string]any) { m["source_id"] = "fictional-other" }, "version": func(m map[string]any) { m["config_version"] = 2 }, "epoch": func(m map[string]any) { m["epoch"] = "00000000-0000-4000-8000-000000000002" }, "sequence": func(m map[string]any) { m["sequence"] = 2 }, "hash": func(m map[string]any) { m["payload_hash"] = strings.Repeat("a", 64) }, "future": func(m map[string]any) { m["received_at"] = "2026-01-01T00:11:00.000001Z" }, "before observed": func(m map[string]any) { m["received_at"] = "2026-01-01T00:09:00.000000Z" }, "precision": func(m map[string]any) { m["received_at"] = "2026-01-01T00:11:00.000Z" }, "null": func(m map[string]any) { m["received_at"] = nil }, "number": func(m map[string]any) { m["sequence"] = "1" }}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			s, m, now := sourceContractAckFixture(t)
			change(m)
			raw, _ := json.Marshal(m)
			if _, err := DecodeSourceContractAck(raw, s, now); err != ErrSourceContractAck {
				t.Fatal("fixed rejection")
			}
		})
	}
	s, m, now := sourceContractAckFixture(t)
	raw, _ := json.Marshal(m)
	if _, err := DecodeSourceContractAck(raw, s, now.Add(24*time.Hour)); err != nil {
		t.Fatal("original ACK need not be fresh", err)
	}
	for _, raw := range [][]byte{nil, []byte(`{"sequence":1,"sequence":1}`), bytes.Repeat([]byte(" "), 4097)} {
		if _, err := DecodeSourceContractAck(raw, s, now); err != ErrSourceContractAck {
			t.Fatal("raw ACK")
		}
	}
}
func TestSourceContractSenderMalformedResponsesAndDenied(t *testing.T) {
	s, ack, now := sourceContractAckFixture(t)
	good, _ := json.Marshal(map[string]any{"data": ack, "meta": map[string]any{"request_id": "fictional-request"}})
	for _, status := range []int{401, 403, 409, 429, 503, 302} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			calls := 0
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				w.Header().Set("Location", "https://fictional.invalid/secret")
				w.WriteHeader(status)
			}))
			defer server.Close()
			c, _ := NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
			if _, err := c.SubmitSourceContractSnapshot(context.Background(), s); err == nil || calls != 1 {
				t.Fatal("must deny without redirect")
			}
		})
	}
	for _, raw := range [][]byte{[]byte(`{"data":null,"meta":{"request_id":"fictional"}}`), []byte(`{"data":{},"meta":{}}`), append(good, []byte("{}")...), []byte(`{"data":{},"data":{},"meta":{}}`), bytes.Repeat([]byte(" "), 16385)} {
		server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(raw) }))
		c, _ := NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
		if _, err := c.SubmitSourceContractSnapshot(context.Background(), s); err != ErrSourceContractAck {
			t.Fatal("closed envelope rejection", err)
		}
		server.Close()
	}
}
