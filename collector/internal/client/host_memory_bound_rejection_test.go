package client

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestHostMemoryActualRejectionIsBoundToCompleteFrozenRequest(t *testing.T) {
	s, _, now := memoryFixture(t)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(422)
		json.NewEncoder(w).Encode(map[string]any{"error": map[string]any{"code": "validation", "message": "fictional rejection"}, "meta": map[string]any{"request_id": "fictional-request"}})
	}))
	defer server.Close()
	c, _ := NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
	_, err := c.SubmitHostMemorySnapshot(context.Background(), s)
	var bound interface{ ForSnapshot(HostMemorySnapshot) bool }
	if !errors.As(err, &bound) || !bound.ForSnapshot(s) {
		t.Fatal("actual native bound rejection", err)
	}
	s.Sequence = 99
	s.PayloadHash = "changed"
	if !bound.ForSnapshot(s) {
		t.Fatal("mutable fields replaced frozen identity")
	}
	different, err := NewHostMemoryTelemetry(s)
	if err != nil {
		t.Fatal(err)
	}
	if bound.ForSnapshot(different) {
		t.Fatal("other frame could retire original")
	}
}
