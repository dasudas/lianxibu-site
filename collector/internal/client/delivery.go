package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

var ErrUntrustedAck = errors.New("collector acknowledgement does not match frozen batch")
var ErrSupersededAttempt = errors.New("control attempt superseded")

type FrozenObservation struct {
	RecordID      string
	RunID         string
	MemberID      string
	ObservationID string
	PayloadHash   string
	Wire          []byte
}
type ObservationAck struct {
	ObservationID string  `json:"observation_id"`
	MemberID      string  `json:"member_id"`
	Status        string  `json:"status"`
	Code          *string `json:"code"`
	ReceiptRef    *string `json:"receipt_ref"`
	AcceptedAt    *string `json:"accepted_at"`
}
type ControlAttempt struct {
	ID             string
	BusinessID     string
	RunID          string
	Kind           string
	IdempotencyKey string
	BusinessHash   string
	Wire           []byte
}
type DeliveryQueue interface {
	NextObservationBatch(context.Context, int, int) ([]FrozenObservation, error)
	ApplyObservationAcks(context.Context, []FrozenObservation, []ObservationAck) error
	RetryObservations(context.Context, []FrozenObservation) error
	QuarantineObservations(context.Context, []FrozenObservation) error
	NextControlAttempt(context.Context) (*ControlAttempt, error)
	RetryControlAttempt(context.Context, ControlAttempt) error
	ApplyControlReceipt(context.Context, ControlAttempt, ControlResult) (bool, error)
	StopControlAttempt(context.Context, ControlAttempt, string) error
	QuarantineControl(context.Context, ControlAttempt, string) error
	ReconcileBusiness(context.Context, string, string, string) (bool, error)
}

type Delivery struct {
	client *Client
	queue  DeliveryQueue
}

func NewDelivery(c *Client, q DeliveryQueue) (*Delivery, error) {
	if c == nil || q == nil {
		return nil, errors.New("delivery client and queue required")
	}
	return &Delivery{client: c, queue: q}, nil
}

// UploadOneBatch never treats HTTP 200 alone as an accepted observation.
func (d *Delivery) UploadOneBatch(ctx context.Context) (int, error) {
	batch, err := d.queue.NextObservationBatch(ctx, 10, 512<<10)
	if err != nil || len(batch) == 0 {
		return 0, err
	}
	if len(batch) > 10 {
		_ = d.queue.RetryObservations(ctx, batch)
		return 0, ErrUntrustedAck
	}
	var body bytes.Buffer
	body.WriteString(`{"observations":[`)
	for index, item := range batch {
		decoded, err := DecodeObservation(item.Wire)
		if err != nil || decoded.ID != item.ObservationID || decoded.MemberID != item.MemberID ||
			decoded.RunID != item.RunID || decoded.PayloadHash != item.PayloadHash {
			_ = d.queue.RetryObservations(ctx, batch)
			return 0, ErrUntrustedAck
		}
		if index > 0 {
			body.WriteByte(',')
		}
		body.Write(item.Wire)
	}
	body.WriteString(`]}`)
	if body.Len() > 512<<10 {
		_ = d.queue.RetryObservations(ctx, batch)
		return 0, ErrUntrustedAck
	}
	raw, err := d.client.request(ctx, http.MethodPost, "/observations", "", body.Bytes())
	if err != nil {
		if errors.Is(err, ErrIdentityConflict) {
			if quarantineErr := d.queue.QuarantineObservations(ctx, batch); quarantineErr != nil {
				return 0, quarantineErr
			}
			return 0, err
		}
		_ = d.queue.RetryObservations(ctx, batch)
		return 0, err
	}
	var result struct {
		Acks []ObservationAck `json:"acks"`
	}
	if err := decodeData(raw, &result); err != nil {
		_ = d.queue.RetryObservations(ctx, batch)
		return 0, err
	}
	if err := validateAcks(batch, result.Acks); err != nil {
		_ = d.queue.RetryObservations(ctx, batch)
		return 0, err
	}
	if err := d.queue.ApplyObservationAcks(ctx, batch, result.Acks); err != nil {
		return 0, err
	}
	return len(batch), nil
}

func validateAcks(batch []FrozenObservation, acks []ObservationAck) error {
	if len(batch) != len(acks) {
		return ErrUntrustedAck
	}
	expected := map[string]FrozenObservation{}
	for _, item := range batch {
		if _, exists := expected[item.ObservationID]; exists {
			return ErrUntrustedAck
		}
		expected[item.ObservationID] = item
	}
	seen := map[string]bool{}
	for _, ack := range acks {
		item, ok := expected[ack.ObservationID]
		if !ok || seen[ack.ObservationID] || item.MemberID != ack.MemberID {
			return ErrUntrustedAck
		}
		seen[ack.ObservationID] = true
		switch ack.Status {
		case "accepted", "duplicate":
			if ack.ReceiptRef == nil || *ack.ReceiptRef == "" || ack.AcceptedAt == nil {
				return ErrUntrustedAck
			}
			if _, err := microTime(*ack.AcceptedAt); err != nil {
				return ErrUntrustedAck
			}
		case "rejected", "blocked", "retryable":
		default:
			return ErrUntrustedAck
		}
	}
	return nil
}

// DeliverOneControl sends only a frozen attempt; business rows are never HTTP payloads.
func (d *Delivery) DeliverOneControl(ctx context.Context) (bool, error) {
	attempt, err := d.queue.NextControlAttempt(ctx)
	if err != nil || attempt == nil {
		return false, err
	}
	if attempt.ID == "" || attempt.BusinessID == "" || attempt.IdempotencyKey == "" || len(attempt.Wire) == 0 ||
		attempt.RunID == "" {
		_ = d.queue.QuarantineControl(ctx, *attempt, "invalid_frozen_attempt")
		return false, ErrUntrustedAck
	}
	method, path, err := controlPath(*attempt)
	if err != nil {
		_ = d.queue.QuarantineControl(ctx, *attempt, "invalid_control_path")
		return false, err
	}
	raw, err := d.client.request(ctx, method, path, attempt.IdempotencyKey, attempt.Wire)
	if err != nil {
		if errors.Is(err, ErrStaleClaim) {
			_ = d.queue.StopControlAttempt(ctx, *attempt, "stale_claim")
		} else if errors.Is(err, ErrRunFrozen) || (errors.Is(err, ErrRevisionConflict) && attempt.Kind == "member_action") {
			_ = d.queue.QuarantineControl(ctx, *attempt, "control_conflict")
		} else if errors.Is(err, ErrRevisionConflict) {
			_ = d.queue.StopControlAttempt(ctx, *attempt, "revision_conflict")
		} else {
			_ = d.queue.RetryControlAttempt(ctx, *attempt)
		}
		return false, err
	}
	var result ControlResult
	if err := decodeData(raw, &result); err != nil {
		_ = d.queue.RetryControlAttempt(ctx, *attempt)
		return false, err
	}
	if result.BusinessHash != attempt.BusinessHash || result.RunID == nil || *result.RunID != attempt.RunID || result.ReceiptRef == "" {
		_ = d.queue.RetryControlAttempt(ctx, *attempt)
		return false, ErrUntrustedAck
	}
	applied, err := d.queue.ApplyControlReceipt(ctx, *attempt, result)
	if err != nil {
		return false, err
	}
	if !applied {
		return false, ErrSupersededAttempt
	}
	return true, nil
}

func controlPath(attempt ControlAttempt) (string, string, error) {
	if strings.ContainsAny(attempt.RunID, "/\\?#") {
		return "", "", ErrUntrustedAck
	}
	base := "/runs/" + url.PathEscape(attempt.RunID)
	switch attempt.Kind {
	case "lease_action":
		return http.MethodPut, base + "/claim", nil
	case "manifest":
		return http.MethodPut, base + "/manifest", nil
	case "run_result":
		return http.MethodPut, base + "/result", nil
	case "member_action":
		value, err := ParseStrict(attempt.Wire)
		if err != nil {
			return "", "", err
		}
		outer, err := object(value, "business", "attempt")
		if err != nil {
			return "", "", err
		}
		business, ok := outer["business"].(map[string]any)
		if !ok || business["run_id"] != attempt.RunID || business["action"] != "seal" {
			return "", "", ErrUntrustedAck
		}
		member, ok := business["member_id"].(string)
		if !ok || member == "" || strings.ContainsAny(member, "/\\?#") {
			return "", "", ErrUntrustedAck
		}
		return http.MethodPost, base + "/members/" + url.PathEscape(member) + "/actions", nil
	default:
		return "", "", fmt.Errorf("unsupported control delivery kind %s", attempt.Kind)
	}
}

// Inspect a control attempt without changing its bytes when retried after a lost ACK.
func DecodeFrozenAttempt(raw []byte, nodeID, operation string) (string, string, error) {
	value, err := ParseStrict(raw)
	if err != nil {
		return "", "", err
	}
	outer, err := object(value, "business", "attempt")
	if err != nil {
		return "", "", err
	}
	businessHash, err := Hash(ControlBusinessHash, map[string]any{"operation": operation, "business": outer["business"]})
	if err != nil {
		return "", "", err
	}
	attemptHash, err := Hash(ControlAttemptHash, map[string]any{"node_id": nodeID, "operation": operation,
		"business_hash": businessHash, "attempt": outer["attempt"]})
	return businessHash, attemptHash, err
}

func DecodeControlBusiness(raw []byte) (map[string]any, error) {
	var value map[string]any
	if _, err := ParseStrict(raw); err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, &value); err != nil {
		return nil, err
	}
	return value, nil
}
