package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
	"time"
)

const fixedNow = "2026-09-30T08:00:00.000000Z"
const later = "2026-09-30T09:00:00.000000Z"

type memoryClaims struct {
	claims []RunClaim
	fail   bool
}

func (m *memoryClaims) PersistClaim(c RunClaim) error {
	if m.fail {
		return errors.New("disk unavailable")
	}
	m.claims = append(m.claims, c)
	return nil
}

func configFixture(until string) map[string]any {
	return map[string]any{"protocol_revision": "0.11", "valid_until": until, "next_cursor": nil,
		"sources": []any{map[string]any{"source_id": "source", "config_version": 1, "activation_stage": "production",
			"config": map[string]any{"adapter": "rss", "entry_urls": []string{"https://example.test/rss"},
				"identity_rule": "guid", "window_rule": "published_at", "parser_version": "1", "max_items": 100,
				"max_response_bytes": 1048576, "allowed_hosts": []string{"example.test"}, "valid_until": until, "auth_secret_ref": nil}}}}
}
func claimFixture(token string) map[string]any {
	source := configFixture(later)["sources"].([]any)[0]
	return map[string]any{"run": map[string]any{"run_id": "run-1", "source_id": "source",
		"run_revision": 1, "config_version": 1, "run_kind": "daily", "business_date": "2026-09-29",
		"generation": 1, "collection_reason": "daily", "fetch_mode": "daily_delta",
		"window_start": "2026-09-29T00:00:00.000000Z", "window_end": "2026-09-30T00:00:00.000000Z"},
		"config": source, "claim_token": token, "lease_until": later, "current_lease_valid": true}
}
func envelope(data any) []byte {
	raw, _ := json.Marshal(map[string]any{"data": data, "meta": map[string]any{"request_id": "test"}})
	return raw
}

func TestConfigCacheStopsNewClaimsAfterExpiry(t *testing.T) {
	now, _ := microTime(fixedNow)
	available := true
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer fictional-token" {
			t.Error("missing node auth")
		}
		if !available {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.Write(envelope(configFixture(later)))
	}))
	defer server.Close()
	client, err := NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.Config(context.Background()); err != nil {
		t.Fatal(err)
	}
	available = false
	if _, err := client.Config(context.Background()); err != nil {
		t.Fatal("valid cache was not used", err)
	}
	now, _ = microTime(later)
	if _, err := client.Config(context.Background()); !errors.Is(err, ErrConfigExpired) {
		t.Fatal("expired cache accepted", err)
	}
	if _, err := client.Claim(context.Background(), "claim-key", []string{"source"}, &memoryClaims{}); !errors.Is(err, ErrConfigExpired) {
		t.Fatal("expired config allowed a new claim", err)
	}
}

func TestClaimPersistsBeforeAckAndLeaseRetriesKeepOriginalIdentity(t *testing.T) {
	now, _ := microTime(fixedNow)
	var claims *memoryClaims = &memoryClaims{}
	var sent [][]byte
	var keys []string
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/collector/v1/config":
			w.Write(envelope(configFixture(later)))
		case "/api/collector/v1/run-claims":
			w.Write(envelope(map[string]any{"runs": []any{claimFixture("token-old")}}))
		case "/api/collector/v1/runs/run-1/claim":
			if len(claims.claims) != 1 {
				t.Error("ACK preceded durable claim")
			}
			var wire map[string]any
			json.NewDecoder(r.Body).Decode(&wire)
			body, _ := json.Marshal(wire)
			sent = append(sent, body)
			keys = append(keys, r.Header.Get("Idempotency-Key"))
			business := wire["business"]
			hash, _ := Hash(ControlBusinessHash, business)
			w.Write(envelope(map[string]any{"business_hash": hash, "run_id": "run-1", "run_revision": 2,
				"current_lease_valid": true, "lease_until": later, "receipt_ref": "receipt"}))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	client, _ := NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
	got, err := client.Claim(context.Background(), "claim-key", []string{"source"}, claims)
	if err != nil || len(got) != 1 || len(claims.claims) != 1 {
		t.Fatal("claim not durable", err)
	}
	action, err := PrepareLeaseAction("ack", got[0], "ack-key", now)
	if err != nil {
		t.Fatal(err)
	}
	first, err := client.SendLeaseAction(context.Background(), action)
	if err != nil || !first.CurrentLeaseValid {
		t.Fatal("ACK failed", err)
	}
	if _, err := client.SendLeaseAction(context.Background(), action); err != nil {
		t.Fatal("ACK replay failed", err)
	}
	if !bytes.Equal(sent[0], sent[1]) || !reflect.DeepEqual(keys, []string{"ack-key", "ack-key"}) {
		t.Fatal("ACK retry changed bytes or key")
	}
	renew, err := PrepareLeaseAction("renew", got[0], "renew-key", now)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.SendLeaseAction(context.Background(), renew); err != nil {
		t.Fatal(err)
	}
	if keys[2] != "renew-key" {
		t.Fatal("new renew reused ACK identity")
	}
}

func TestFailedPersistencePreventsAckAndExpiredTokenCannotMoveToNewLease(t *testing.T) {
	now, _ := microTime(fixedNow)
	ackCalls := 0
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/collector/v1/config":
			w.Write(envelope(configFixture(later)))
		case "/api/collector/v1/run-claims":
			w.Write(envelope(map[string]any{"runs": []any{claimFixture("token-old")}}))
		case "/api/collector/v1/runs/run-1/claim":
			ackCalls++
			w.WriteHeader(http.StatusConflict)
		}
	}))
	defer server.Close()
	client, _ := NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
	if _, err := client.Claim(context.Background(), "claim-key", nil, &memoryClaims{fail: true}); err == nil {
		t.Fatal("lost claim accepted")
	}
	if ackCalls != 0 {
		t.Fatal("ACK sent before persistence")
	}
	claim := RunClaim{}
	claim.Run.RunID = "run-1"
	claim.CurrentLeaseValid = true
	token, lease := "token-old", later
	claim.ClaimToken = &token
	claim.LeaseUntil = &lease
	old, err := PrepareLeaseAction("renew", claim, "old-renew", now)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.SendLeaseAction(context.Background(), old); !errors.Is(err, ErrStaleClaim) {
		t.Fatal("stale token accepted", err)
	}
	now, _ = microTime(later)
	if _, err := PrepareLeaseAction("renew", claim, "new-renew", now); !errors.Is(err, ErrStaleClaim) {
		t.Fatal("expired token migrated", err)
	}
}

func TestConfigPaginationAndPermissionRevocation(t *testing.T) {
	now, _ := microTime(fixedNow)
	forbidden := false
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if forbidden {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		part := configFixture(later)
		if r.URL.Query().Get("cursor") == "next" {
			part["sources"] = []any{}
			part["next_cursor"] = nil
		} else {
			part["next_cursor"] = "next"
		}
		w.Write(envelope(part))
	}))
	defer server.Close()
	client, _ := NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
	got, err := client.Config(context.Background())
	if err != nil || len(got.Sources) != 1 {
		t.Fatal("pagination did not complete", err)
	}
	got.Sources[0].SourceID = "tampered"
	forbidden = true
	if _, err := client.Config(context.Background()); !errors.Is(err, ErrPermission) {
		t.Fatal("revocation used cached config", err)
	}
	forbidden = false
	got, err = client.Config(context.Background())
	if err != nil || got.Sources[0].SourceID != "source" {
		t.Fatal("caller changed cached config", err)
	}
}
