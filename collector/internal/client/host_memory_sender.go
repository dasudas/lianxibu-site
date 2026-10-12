package client

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"time"
)

var ErrHostMemoryAck = errors.New("untrusted collector host memory acknowledgement")

type HostMemoryAck struct {
	CaptureEpoch string
	Sequence     uint64
	PayloadHash  string
	ReceivedAt   string
	wireBytes    []byte
}

func (HostMemoryAck) String() string            { return "HostMemoryAck[redacted]" }
func (a HostMemoryAck) FrozenWireBytes() []byte { return append([]byte(nil), a.wireBytes...) }
func DecodeHostMemoryAck(raw []byte, snapshot HostMemorySnapshot, now time.Time) (HostMemoryAck, error) {
	invalid := func() (HostMemoryAck, error) { return HostMemoryAck{}, ErrHostMemoryAck }
	original, err := DecodeHostMemoryTelemetry(snapshot.FrozenWireBytes())
	if err != nil || len(raw) == 0 || len(raw) > 4096 {
		return invalid()
	}
	value, err := ParseStrict(raw)
	if err != nil {
		return invalid()
	}
	m, err := object(value, "capture_epoch", "sequence", "payload_hash", "received_at")
	if err != nil {
		return invalid()
	}
	epoch, ok := m["capture_epoch"].(string)
	if !ok || epoch != original.CaptureEpoch {
		return invalid()
	}
	sequence, err := wireInteger(m["sequence"])
	if err != nil || sequence != original.Sequence {
		return invalid()
	}
	hash, err := wireHash(m["payload_hash"])
	if err != nil || hash != original.PayloadHash {
		return invalid()
	}
	received, err := sourceContractInstant(m["received_at"])
	if err != nil || received.After(now) || received.Format(SourceContractTime) < original.ObservedAt {
		return invalid()
	}
	canonical, err := CanonicalBytes(value)
	if err != nil {
		return invalid()
	}
	return HostMemoryAck{epoch, sequence, hash, received.Format(SourceContractTime), canonical}, nil
}

// Frozen validated bytes and their exact ACK identify the sample; no healthy claim is made.
func (c *Client) SubmitHostMemorySnapshot(ctx context.Context, snapshot HostMemorySnapshot) (HostMemoryAck, error) {
	original, err := DecodeHostMemoryTelemetry(snapshot.FrozenWireBytes())
	if err != nil {
		return HostMemoryAck{}, ErrHostMemoryTelemetry
	}
	raw, err := c.hostMemoryRequest(ctx, original.FrozenWireBytes())
	if err != nil {
		return HostMemoryAck{}, err
	}
	if len(raw) > 16384 {
		return HostMemoryAck{}, ErrHostMemoryAck
	}
	value, err := ParseStrict(raw)
	if err != nil {
		return HostMemoryAck{}, ErrHostMemoryAck
	}
	envelope, err := object(value, "data", "meta")
	if err != nil {
		return HostMemoryAck{}, ErrHostMemoryAck
	}
	meta, err := object(envelope["meta"], "request_id")
	if err != nil {
		return HostMemoryAck{}, ErrHostMemoryAck
	}
	if _, err = wireID(meta["request_id"], false); err != nil {
		return HostMemoryAck{}, ErrHostMemoryAck
	}
	ack, err := CanonicalBytes(envelope["data"])
	if err != nil {
		return HostMemoryAck{}, ErrHostMemoryAck
	}
	return DecodeHostMemoryAck(ack, original, c.now())
}

// Only an actual closed native server rejection can classify an aged sample for retirement.
type HostMemoryRejected struct {
	status      int
	payloadHash string
}

func (e *HostMemoryRejected) Error() string { return "native collector memory snapshot rejected" }
func (e *HostMemoryRejected) Status() int   { return e.status }
func (c *Client) hostMemoryRequest(ctx context.Context, body []byte) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+"/host-memory", bytes.NewReader(body))
	if err != nil {
		return nil, ErrHostMemoryTelemetry
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, ErrRetryable
	}
	defer resp.Body.Close()
	if resp.StatusCode == 401 || resp.StatusCode == 403 {
		return nil, ErrPermission
	}
	if resp.StatusCode == 429 || resp.StatusCode >= 500 {
		return nil, ErrRetryable
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 16385))
	if err != nil || len(raw) > 16384 {
		return nil, ErrHostMemoryAck
	}
	if resp.StatusCode == 409 || resp.StatusCode == 422 {
		if len(raw) > 4096 {
			return nil, ErrHostMemoryAck
		}
		value, err := ParseStrict(raw)
		if err != nil {
			return nil, ErrHostMemoryAck
		}
		root, err := object(value, "error", "meta")
		if err != nil {
			return nil, ErrHostMemoryAck
		}
		failure, err := object(root["error"], "code", "message")
		if err != nil {
			return nil, ErrHostMemoryAck
		}
		meta, err := object(root["meta"], "request_id")
		if err != nil {
			return nil, ErrHostMemoryAck
		}
		if _, err = wireID(meta["request_id"], false); err != nil {
			return nil, ErrHostMemoryAck
		}
		message, ok := failure["message"].(string)
		expected := "validation"
		if resp.StatusCode == 409 {
			expected = "snapshot_conflict"
		}
		if !ok || len(message) == 0 || len(message) > 1024 || failure["code"] != expected {
			return nil, ErrHostMemoryAck
		}
		original, err := DecodeHostMemoryTelemetry(body)
		if err != nil {
			return nil, ErrHostMemoryAck
		}
		return nil, &HostMemoryRejected{resp.StatusCode, original.PayloadHash}
	}
	if resp.StatusCode != 200 {
		return nil, ErrHostMemoryAck
	}
	return raw, nil
}

// A native rejection belongs to the exact frozen request, including its entire original ACTION hash.
func (e *HostMemoryRejected) ForSnapshot(snapshot HostMemorySnapshot) bool {
	if e == nil || (e.status != 409 && e.status != 422) {
		return false
	}
	original, err := DecodeHostMemoryTelemetry(snapshot.FrozenWireBytes())
	return err == nil && e.payloadHash == original.PayloadHash
}
