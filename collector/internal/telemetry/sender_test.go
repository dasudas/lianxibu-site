package telemetry

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHeartbeatUsesExactCollectorPathAndFailureDoesNotExposeBody(t *testing.T) {
	fail := false
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/collector/v1/heartbeat" || r.Header.Get("Authorization") != "Bearer fictional-token" {
			t.Error("wrong heartbeat request")
		}
		body, _ := io.ReadAll(r.Body)
		var fields map[string]any
		if json.Unmarshal(body, &fields) != nil || len(fields) != 7 || fields["free_bytes"] != float64(2<<30) {
			t.Error("heartbeat wire fields drifted")
		}
		if fail {
			w.WriteHeader(http.StatusServiceUnavailable)
			w.Write([]byte("private server detail"))
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	sender, err := NewHTTPSender(server.URL, "fictional-token", server.Client())
	if err != nil {
		t.Fatal(err)
	}
	beat := Heartbeat{ObservedAt: "2026-09-30T08:00:00.000000Z", FreeBytes: 2 << 30}
	if err := sender.SendHeartbeat(context.Background(), beat); err != nil {
		t.Fatal(err)
	}
	fail = true
	if err := sender.SendHeartbeat(context.Background(), beat); err == nil || strings.Contains(err.Error(), "private server detail") {
		t.Fatal("failed heartbeat leaked response", err)
	}
}
