package client

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"time"
)

const SourceContractAckSchema = "source-contract-telemetry-ack-v1"

var ErrSourceContractAck = errors.New("untrusted source contract acknowledgement")

type SourceContractAck struct {
	SourceID      string
	ConfigVersion uint64
	Epoch         string
	Sequence      uint64
	PayloadHash   string
	ReceivedAt    string
	wireBytes     []byte
}

func (SourceContractAck) String() string            { return "SourceContractAck[redacted]" }
func (a SourceContractAck) FrozenWireBytes() []byte { return append([]byte(nil), a.wireBytes...) }

// DecodeSourceContractAck verifies the permanent identity, not later health state.
func DecodeSourceContractAck(raw []byte, snapshot SourceContractSnapshot, now time.Time) (SourceContractAck, error) {
	invalid := func() (SourceContractAck, error) { return SourceContractAck{}, ErrSourceContractAck }
	original, err := DecodeSourceContractTelemetry(snapshot.FrozenWireBytes())
	if err != nil || len(raw) == 0 || len(raw) > 4096 {
		return invalid()
	}
	value, err := ParseStrict(raw)
	if err != nil {
		return invalid()
	}
	m, err := object(value, "schema_version", "source_id", "config_version", "epoch", "sequence", "payload_hash", "received_at")
	if err != nil || m["schema_version"] != SourceContractAckSchema {
		return invalid()
	}
	id, err := wireID(m["source_id"], true)
	if err != nil {
		return invalid()
	}
	version, err := wireInteger(m["config_version"])
	if err != nil {
		return invalid()
	}
	seq, err := wireInteger(m["sequence"])
	if err != nil {
		return invalid()
	}
	hash, err := wireHash(m["payload_hash"])
	if err != nil {
		return invalid()
	}
	epoch, ok := m["epoch"].(string)
	if !ok || id != original.SourceID || version != original.ConfigVersion || seq != original.Sequence || epoch != original.Epoch || hash != original.PayloadHash {
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
	return SourceContractAck{SourceID: id, ConfigVersion: version, Epoch: epoch, Sequence: seq, PayloadHash: hash, ReceivedAt: received.Format(SourceContractTime), wireBytes: canonical}, nil
}

// SubmitSourceContractSnapshot sends only frozen validated bytes with the node Bearer.
// Lost ACKs replay the same identity; this operation does not claim a source run.
func (c *Client) SubmitSourceContractSnapshot(ctx context.Context, snapshot SourceContractSnapshot) (SourceContractAck, error) {
	original, err := DecodeSourceContractTelemetry(snapshot.FrozenWireBytes())
	if err != nil {
		return SourceContractAck{}, ErrSourceContractTelemetry
	}
	raw, err := c.request(ctx, http.MethodPut, "/source-contract-telemetry/"+url.PathEscape(original.SourceID), "", original.FrozenWireBytes())
	if err != nil {
		return SourceContractAck{}, err
	}
	if len(raw) > 16384 {
		return SourceContractAck{}, ErrSourceContractAck
	}
	value, err := ParseStrict(raw)
	if err != nil {
		return SourceContractAck{}, ErrSourceContractAck
	}
	envelope, err := object(value, "data", "meta")
	if err != nil {
		return SourceContractAck{}, ErrSourceContractAck
	}
	meta, err := object(envelope["meta"], "request_id")
	if err != nil {
		return SourceContractAck{}, ErrSourceContractAck
	}
	if _, err = wireID(meta["request_id"], false); err != nil {
		return SourceContractAck{}, ErrSourceContractAck
	}
	ack, err := CanonicalBytes(envelope["data"])
	if err != nil {
		return SourceContractAck{}, ErrSourceContractAck
	}
	return DecodeSourceContractAck(ack, original, c.now())
}
