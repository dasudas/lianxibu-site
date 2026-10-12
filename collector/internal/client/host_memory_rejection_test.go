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

func TestHostMemoryNativeRejectionRequiresActualStatusAndClosedBoundedEnvelope(t *testing.T) {
	s, _, now := memoryFixture(t)
	for _, status := range []int{409, 422} {
		for _, valid := range []bool{true, false} {
			code := "validation"
			if status == 409 {
				code = "snapshot_conflict"
			}
			body := map[string]any{"error": map[string]any{"code": code, "message": "fictional rejection"}, "meta": map[string]any{"request_id": "fictional-request"}}
			if !valid {
				body["healthy"] = true
			}
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(status); json.NewEncoder(w).Encode(body) }))
			c, _ := NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
			_, err := c.SubmitHostMemorySnapshot(context.Background(), s)
			server.Close()
			var rejected interface{ Status() int }
			if valid {
				if !errors.As(err, &rejected) || rejected.Status() != status {
					t.Fatal("actual native rejection", status, err)
				}
			} else if err != ErrHostMemoryAck || errors.As(err, &rejected) {
				t.Fatal("untrusted response retirement")
			}
		}
	}
}
func TestHostMemoryPermissionOrTransientOrRedirectCannotRetirePendingSample(t *testing.T) {
	s, _, now := memoryFixture(t)
	for _, status := range []int{401, 403, 429, 503, 302, 400} {
		server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Location", "https://fictional.invalid/redirect")
			w.WriteHeader(status)
		}))
		c, _ := NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
		_, err := c.SubmitHostMemorySnapshot(context.Background(), s)
		server.Close()
		var rejected interface{ Status() int }
		if err == nil || errors.As(err, &rejected) {
			t.Fatal("not a qualified rejection", status)
		}
		if (status == 401 || status == 403) && err != ErrPermission {
			t.Fatal("native authority", err)
		}
		if (status == 429 || status == 503) && err != ErrRetryable {
			t.Fatal("native transient", err)
		}
	}
}
