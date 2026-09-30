package client

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"os"
	"testing"
	"time"
)

// This opt-in test talks to the real Java collector runtime started by CollectorRuntimeHttpTest.
type loopbackHTTP struct{}

func (loopbackHTTP) RoundTrip(request *http.Request) (*http.Response, error) {
	if request.URL.Hostname() != "127.0.0.1" {
		return nil, errors.New("interop transport only permits loopback")
	}
	copyRequest := request.Clone(request.Context())
	copyURL := *request.URL
	copyURL.Scheme = "http"
	copyRequest.URL = &copyURL
	return http.DefaultTransport.RoundTrip(copyRequest)
}

func TestJavaInterop(t *testing.T) {
	origin := os.Getenv("V02_BASE_URL")
	if origin == "" {
		t.Skip("Java HTTP fixture not active")
	}
	parsed, err := url.Parse(origin)
	if err != nil || parsed.Scheme != "https" || parsed.Hostname() != "127.0.0.1" || parsed.Port() == "" {
		t.Fatal("interop requires the Java loopback fixture")
	}
	token := os.Getenv("V02_NODE_TOKEN")
	c, err := NewClient(origin, token, &http.Client{Transport: loopbackHTTP{}, Timeout: 15 * time.Second}, time.Now)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	mode := os.Getenv("V02_MODE")
	if mode == "revoked" {
		if _, err := c.Config(ctx); !errors.Is(err, ErrPermission) {
			t.Fatalf("revoked Java credential was accepted: %v", err)
		}
		return
	}
	snapshot, err := c.Config(ctx)
	if err != nil || snapshot.ProtocolRevision != "0.11" {
		t.Fatalf("Java config rejected by Go: %v", err)
	}
	if mode == "replay" {
		wire, err := os.ReadFile(os.Getenv("V02_FROZEN_FILE"))
		if err != nil {
			t.Fatal(err)
		}
		observation, err := DecodeObservation(wire)
		if err != nil {
			t.Fatal(err)
		}
		queue := &memoryDeliveryQueue{observations: []FrozenObservation{{
			RecordID: "late-replay", RunID: observation.RunID, MemberID: observation.MemberID,
			ObservationID: observation.ID, PayloadHash: observation.PayloadHash, Wire: wire,
		}}}
		delivery, err := NewDelivery(c, queue)
		if err != nil {
			t.Fatal(err)
		}
		if count, err := delivery.UploadOneBatch(ctx); err != nil || count != 1 || queue.accepted != 1 {
			t.Fatalf("terminal 90-day Java ACK did not confirm frozen Go bytes: %v", err)
		}
		return
	}
	if mode == "stale" {
		frozen, err := os.ReadFile(os.Getenv("V02_CLAIM_FILE"))
		if err != nil {
			t.Fatal(err)
		}
		var claim RunClaim
		if err := json.Unmarshal(frozen, &claim); err != nil {
			t.Fatal(err)
		}
		action, err := PrepareLeaseAction("renew", claim, "go-stale-lease", time.Now())
		if err != nil {
			t.Fatal(err)
		}
		if _, err := c.SendLeaseAction(ctx, action); !errors.Is(err, ErrStaleClaim) {
			t.Fatalf("rotated Java lease accepted old Go token: %v", err)
		}
		return
	}
	if mode != "fresh" {
		t.Fatal("unknown Java interop mode")
	}
	store := &memoryClaims{}
	claims, err := c.Claim(ctx, "go-claim", []string{"fixture:go"}, store)
	if err != nil || len(claims) != 1 || len(store.claims) != 1 {
		t.Fatalf("Java claim failed Go validation or durable handoff: %v", err)
	}
	claim := claims[0]
	if claim.Run.RunID != "fixture-go-run" {
		t.Fatal("unexpected Java run identity")
	}
	claimBytes, err := json.Marshal(claim)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(os.Getenv("V02_CLAIM_FILE"), claimBytes, 0600); err != nil {
		t.Fatal(err)
	}
	action, err := PrepareLeaseAction("ack", claim, "go-ack", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	receipt, err := c.SendLeaseAction(ctx, action)
	if err != nil || receipt.ReceiptRef == "" {
		t.Fatalf("Java lease receipt rejected by Go: %v", err)
	}
	var row map[string]any
	for _, sample := range loadWireCases(t) {
		if sample.Schema == "ObservationEnvelope" && sample.Valid {
			candidate, err := ParseStrict(sample.Value)
			if err != nil {
				t.Fatal(err)
			}
			value := candidate.(map[string]any)
			if value["kind"] == "metric" {
				row = value
				break
			}
		}
	}
	if row == nil {
		t.Fatal("metric contract fixture absent")
	}
	row["observation_id"] = "go-metric-observation"
	row["member_id"] = "go-metric-member"
	row["run_id"] = claim.Run.RunID
	row["business_date"] = claim.Run.BusinessDate
	row["source_id"] = claim.Run.SourceID
	row["source_revision"] = claim.Run.ConfigVersion
	row["source_item_id"] = "go-metric-item"
	row["retrieved_at"] = time.Now().UTC().Format("2006-01-02T15:04:05.000000Z")
	payload := row["payload"].(map[string]any)
	payload["parser_version"] = claim.Config.Config.ParserVersion
	payload["config_version"] = claim.Config.ConfigVersion
	delete(row, "payload_hash")
	hash, err := Hash(ObservationHash, row)
	if err != nil {
		t.Fatal(err)
	}
	row["payload_hash"] = hash
	wire, err := json.Marshal(row)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := DecodeObservation(wire); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(os.Getenv("V02_FROZEN_FILE"), wire, 0600); err != nil {
		t.Fatal(err)
	}
	queue := &memoryDeliveryQueue{observations: []FrozenObservation{{
		RecordID: "go-row", RunID: claim.Run.RunID, MemberID: "go-metric-member",
		ObservationID: "go-metric-observation", PayloadHash: hash, Wire: wire,
	}}, failAckOnce: true}
	delivery, err := NewDelivery(c, queue)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := delivery.UploadOneBatch(ctx); err == nil || queue.accepted != 0 {
		t.Fatal("fictional local ACK failure was not exercised")
	}
	for i := 1; i <= 2; i++ {
		count, err := delivery.UploadOneBatch(ctx)
		if err != nil || count != 1 || queue.accepted != i || queue.retried != 0 {
			t.Fatalf("Java observation ACK failed Go frozen-delivery check on attempt %d: %v", i, err)
		}
	}
	row["source_item_id"] = "changed-item-same-observation-id"
	delete(row, "payload_hash")
	conflictHash, err := Hash(ObservationHash, row)
	if err != nil {
		t.Fatal(err)
	}
	row["payload_hash"] = conflictHash
	conflictWire, err := json.Marshal(row)
	if err != nil {
		t.Fatal(err)
	}
	conflictQueue := &memoryDeliveryQueue{observations: []FrozenObservation{{
		RecordID: "conflict-row", RunID: claim.Run.RunID, MemberID: "go-metric-member",
		ObservationID: "go-metric-observation", PayloadHash: conflictHash, Wire: conflictWire,
	}}}
	conflictDelivery, err := NewDelivery(c, conflictQueue)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := conflictDelivery.UploadOneBatch(ctx); !errors.Is(err, ErrIdentityConflict) ||
		conflictQueue.quarantined != 1 || conflictQueue.retried != 0 {
		t.Fatalf("Java hash conflict did not isolate the Go observation: %v", err)
	}
}
