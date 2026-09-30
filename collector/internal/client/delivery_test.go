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

type memoryDeliveryQueue struct {
	observations       []FrozenObservation
	control            *ControlAttempt
	accepted           int
	retried            int
	quarantined        int
	failAckOnce        bool
	failControlAckOnce bool
	controlApplied     bool
	controlCurrent     bool
	stopped            bool
	reconciled         bool
}

func (q *memoryDeliveryQueue) NextObservationBatch(context.Context, int, int) ([]FrozenObservation, error) {
	return q.observations, nil
}
func (q *memoryDeliveryQueue) ApplyObservationAcks(_ context.Context, b []FrozenObservation, acks []ObservationAck) error {
	if q.failAckOnce {
		q.failAckOnce = false
		return errors.New("fictional local ACK commit failure")
	}
	q.accepted += len(acks)
	return nil
}
func (q *memoryDeliveryQueue) RetryObservations(_ context.Context, b []FrozenObservation) error {
	q.retried++
	return nil
}
func (q *memoryDeliveryQueue) QuarantineObservations(_ context.Context, b []FrozenObservation) error {
	q.quarantined += len(b)
	return nil
}
func (q *memoryDeliveryQueue) NextControlAttempt(context.Context) (*ControlAttempt, error) {
	return q.control, nil
}
func (q *memoryDeliveryQueue) RetryControlAttempt(context.Context, ControlAttempt) error { return nil }
func (q *memoryDeliveryQueue) ApplyControlReceipt(_ context.Context, a ControlAttempt, r ControlResult) (bool, error) {
	if q.failControlAckOnce {
		q.failControlAckOnce = false
		return false, errors.New("fictional local control ACK commit failure")
	}
	if !q.controlCurrent {
		return false, nil
	}
	q.controlApplied = true
	return true, nil
}
func (q *memoryDeliveryQueue) StopControlAttempt(context.Context, ControlAttempt, string) error {
	q.stopped = true
	return nil
}
func (q *memoryDeliveryQueue) QuarantineControl(context.Context, ControlAttempt, string) error {
	q.stopped = true
	return nil
}
func (q *memoryDeliveryQueue) ReconcileBusiness(_ context.Context, businessID, hash, receipt string) (bool, error) {
	if businessID == "" || hash == "" || receipt != "readback:"+hash {
		return false, ErrUntrustedAck
	}
	q.reconciled = true
	return true, nil
}

func frozenObservation(t *testing.T, id string) FrozenObservation {
	t.Helper()
	var sample []byte
	for _, wire := range loadWireCases(t) {
		if wire.Schema == "ObservationEnvelope" && wire.Valid {
			sample = wire.Value
			break
		}
	}
	value, err := ParseStrict(sample)
	if err != nil {
		t.Fatal(err)
	}
	row := value.(map[string]any)
	row["observation_id"] = id
	row["member_id"] = "member-" + id
	row["run_id"] = "run-1"
	delete(row, "payload_hash")
	hash, err := Hash(ObservationHash, row)
	if err != nil {
		t.Fatal(err)
	}
	row["payload_hash"] = hash
	raw, err := json.Marshal(row)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := DecodeObservation(raw); err != nil {
		t.Fatal(err)
	}
	return FrozenObservation{RecordID: "row-" + id, RunID: "run-1", MemberID: "member-" + id,
		ObservationID: id, PayloadHash: hash, Wire: raw}
}

func TestUploadRequiresExactPerObservationAckBeforeLocalConfirmation(t *testing.T) {
	one, two := frozenObservation(t, "obs-1"), frozenObservation(t, "obs-2")
	queue := &memoryDeliveryQueue{observations: []FrozenObservation{one, two}}
	partial := true
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if !bytes.Contains(body, one.Wire) || !bytes.Contains(body, two.Wire) {
			t.Error("frozen observation bytes changed")
		}
		ack := func(o FrozenObservation) map[string]any {
			return map[string]any{"observation_id": o.ObservationID, "member_id": o.MemberID,
				"status": "accepted", "code": nil, "receipt_ref": "receipt-" + o.ObservationID,
				"accepted_at": "2026-09-30T08:00:00.000000Z", "projection_decision": nil, "current_article_revision": nil, "comparison_reason": nil}
		}
		acks := []any{ack(one)}
		if !partial {
			acks = append(acks, ack(two))
		}
		w.Write(envelope(map[string]any{"acks": acks}))
	}))
	defer server.Close()
	client, _ := NewClient(server.URL, "fictional-token", server.Client(), time.Now)
	delivery, _ := NewDelivery(client, queue)
	if _, err := delivery.UploadOneBatch(context.Background()); !errors.Is(err, ErrUntrustedAck) || queue.accepted != 0 || queue.retried != 1 {
		t.Fatal("partial ACK accepted", err)
	}
	partial = false
	count, err := delivery.UploadOneBatch(context.Background())
	if err != nil || count != 2 || queue.accepted != 2 {
		t.Fatal("complete durable ACK rejected", err)
	}
}

func TestOldControlAttemptResponseCannotConfirmCurrentBusiness(t *testing.T) {
	business := map[string]any{"run_id": "run-1", "action": "ack"}
	hash, _ := Hash(ControlBusinessHash, map[string]any{"operation": "claim_ack", "business": business})
	body, _ := json.Marshal(map[string]any{"business": business, "attempt": map[string]any{"claim_token": "old-token", "expected_run_revision": nil}})
	queue := &memoryDeliveryQueue{control: &ControlAttempt{ID: "attempt-old", BusinessID: "business", RunID: "run-1", Kind: "lease_action",
		IdempotencyKey: "key-old", BusinessHash: hash, Wire: body}, controlCurrent: false}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/runs/run-1/claim") || r.Header.Get("Idempotency-Key") != "key-old" {
			t.Error("wrong attempt")
		}
		w.Write(envelope(map[string]any{"business_hash": hash, "run_id": "run-1", "receipt_ref": "receipt",
			"run_revision": 2, "current_lease_valid": false, "lease_until": nil, "probe_id": nil, "delivery_state": "claimed"}))
	}))
	defer server.Close()
	client, _ := NewClient(server.URL, "fictional-token", server.Client(), time.Now)
	delivery, _ := NewDelivery(client, queue)
	if _, err := delivery.DeliverOneControl(context.Background()); !errors.Is(err, ErrSupersededAttempt) || queue.controlApplied {
		t.Fatal("old attempt changed new claim", err)
	}
	queue.controlCurrent = true
	if ok, err := delivery.DeliverOneControl(context.Background()); err != nil || !ok || !queue.controlApplied {
		t.Fatal("current attempt not acknowledged", err)
	}
}

func TestStaleControlStopsAttemptAndDoesNotTreatPendingAsAccepted(t *testing.T) {
	queue := &memoryDeliveryQueue{control: &ControlAttempt{ID: "attempt", BusinessID: "business", RunID: "run-1", Kind: "manifest",
		IdempotencyKey: "key", BusinessHash: strings.Repeat("a", 64), Wire: []byte(`{"business":{},"attempt":{}}`)}, controlCurrent: true}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		w.Write([]byte(`{"error":{"code":"stale_claim","message":"expired"},"meta":{"request_id":"test"}}`))
	}))
	defer server.Close()
	client, _ := NewClient(server.URL, "fictional-token", server.Client(), time.Now)
	delivery, _ := NewDelivery(client, queue)
	if _, err := delivery.DeliverOneControl(context.Background()); !errors.Is(err, ErrStaleClaim) || !queue.stopped || queue.controlApplied {
		t.Fatal("stale manifest confirmed", err)
	}
}

func TestControlReadbackRequiresMatchingPersistedBusiness(t *testing.T) {
	part := map[string]any{"run_id": "run-1", "part_no": 0, "part_hash": strings.Repeat("a", 64), "members": []any{}}
	hash, err := Hash(ControlBusinessHash, map[string]any{"operation": "manifest", "business": part})
	if err != nil {
		t.Fatal(err)
	}
	wire, _ := json.Marshal(map[string]any{"business": part, "attempt": map[string]any{"claim_token": "token-a", "expected_run_revision": nil}})
	queue := &memoryDeliveryQueue{}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/collector/v1/runs/run-1/manifest" {
			t.Error("wrong readback route")
		}
		w.Write(envelope(map[string]any{"run_id": "run-1", "parts": []any{part}, "next_cursor": nil}))
	}))
	defer server.Close()
	c, _ := NewClient(server.URL, "fictional-token", server.Client(), time.Now)
	d, _ := NewDelivery(c, queue)
	a := ControlAttempt{BusinessID: "business-1", RunID: "run-1", Kind: "manifest", BusinessHash: hash, Wire: wire}
	if ok, err := d.ReconcileControl(context.Background(), a); err != nil || !ok || !queue.reconciled {
		t.Fatal("matching readback did not confirm", err)
	}
	queue.reconciled = false
	a.BusinessHash = strings.Repeat("b", 64)
	if ok, err := d.ReconcileControl(context.Background(), a); !errors.Is(err, ErrUntrustedAck) || ok || queue.reconciled {
		t.Fatal("mismatched part confirmed", err)
	}
}

func TestResultReadbackLeavesMissingHashPending(t *testing.T) {
	queue := &memoryDeliveryQueue{}
	remoteHash := strings.Repeat("a", 64)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write(envelope(map[string]any{"run_id": "run-1", "result_business_hash": remoteHash}))
	}))
	defer server.Close()
	c, _ := NewClient(server.URL, "fictional-token", server.Client(), time.Now)
	d, _ := NewDelivery(c, queue)
	a := ControlAttempt{BusinessID: "business-1", RunID: "run-1", Kind: "run_result", BusinessHash: strings.Repeat("b", 64)}
	if ok, err := d.ReconcileControl(context.Background(), a); err != nil || ok || queue.reconciled {
		t.Fatal("different result hash confirmed", err)
	}
	a.BusinessHash = remoteHash
	if ok, err := d.ReconcileControl(context.Background(), a); err != nil || !ok || !queue.reconciled {
		t.Fatal("matching result hash not confirmed", err)
	}
}
