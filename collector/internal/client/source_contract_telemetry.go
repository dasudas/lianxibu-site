package client

import (
	"encoding/json"
	"errors"
	"regexp"
	"time"
)

const SourceContractSchema = "source-contract-telemetry-v1"
const SourceContractTime = "2006-01-02T15:04:05.000000Z"

var sourceEpoch = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var ErrSourceContractTelemetry = errors.New("invalid source contract telemetry")

type SourceFailureEvent struct {
	Sequence   uint64 `json:"sequence"`
	OccurredAt string `json:"occurred_at"`
}

func (SourceFailureEvent) String() string { return "SourceFailureEvent[redacted]" }

// FrozenWireBytes, not subsequently mutable fields, identifies a queued snapshot.
type SourceContractSnapshot struct {
	SchemaVersion string               `json:"schema_version"`
	SourceID      string               `json:"source_id"`
	ConfigVersion uint64               `json:"config_version"`
	Epoch         string               `json:"epoch"`
	Sequence      uint64               `json:"sequence"`
	ObservedAt    string               `json:"observed_at"`
	WindowStart   string               `json:"window_start"`
	TotalFailures uint64               `json:"total_failures"`
	Events        []SourceFailureEvent `json:"events"`
	PayloadHash   string               `json:"-"`
	wireBytes     []byte
}

func (SourceContractSnapshot) String() string            { return "SourceContractSnapshot[redacted]" }
func (s SourceContractSnapshot) FrozenWireBytes() []byte { return append([]byte(nil), s.wireBytes...) }

// DecodeSourceContractTelemetry enforces the same closed contract as the server.
func DecodeSourceContractTelemetry(raw []byte) (SourceContractSnapshot, error) {
	invalid := func() (SourceContractSnapshot, error) { return SourceContractSnapshot{}, ErrSourceContractTelemetry }
	if len(raw) == 0 || len(raw) > 32768 {
		return invalid()
	}
	value, err := ParseStrict(raw)
	if err != nil {
		return invalid()
	}
	m, err := object(value, "schema_version", "source_id", "config_version", "epoch", "sequence", "observed_at", "window_start", "total_failures", "events")
	if err != nil || m["schema_version"] != SourceContractSchema {
		return invalid()
	}
	id, err := wireID(m["source_id"], true)
	if err != nil {
		return invalid()
	}
	ver, err := wireInteger(m["config_version"])
	if err != nil || ver == 0 {
		return invalid()
	}
	seq, err := wireInteger(m["sequence"])
	if err != nil || seq == 0 {
		return invalid()
	}
	total, err := wireInteger(m["total_failures"])
	if err != nil {
		return invalid()
	}
	epoch, ok := m["epoch"].(string)
	if !ok || !sourceEpoch.MatchString(epoch) {
		return invalid()
	}
	observed, err := sourceContractInstant(m["observed_at"])
	if err != nil {
		return invalid()
	}
	start, err := sourceContractInstant(m["window_start"])
	if err != nil || !start.Equal(observed.Add(-600*time.Second)) {
		return invalid()
	}
	rows, ok := m["events"].([]any)
	if !ok || len(rows) > 200 {
		return invalid()
	}
	events := make([]SourceFailureEvent, 0, len(rows))
	var previous uint64
	at := start
	for _, row := range rows {
		e, err := object(row, "sequence", "occurred_at")
		if err != nil {
			return invalid()
		}
		count, err := wireInteger(e["sequence"])
		if err != nil || count <= previous || count > total {
			return invalid()
		}
		when, err := sourceContractInstant(e["occurred_at"])
		if err != nil || !when.After(start) || when.Before(at) || when.After(observed) {
			return invalid()
		}
		events = append(events, SourceFailureEvent{count, when.Format(SourceContractTime)})
		previous = count
		at = when
	}
	digest, err := Hash(ActionHash, value)
	if err != nil {
		return invalid()
	}
	canonical, err := CanonicalBytes(value)
	if err != nil {
		return invalid()
	}
	return SourceContractSnapshot{SchemaVersion: SourceContractSchema, SourceID: id, ConfigVersion: ver, Epoch: epoch, Sequence: seq, ObservedAt: observed.Format(SourceContractTime), WindowStart: start.Format(SourceContractTime), TotalFailures: total, Events: events, PayloadHash: digest, wireBytes: canonical}, nil
}
func sourceContractInstant(value any) (time.Time, error) {
	if wireTime(value) != nil {
		return time.Time{}, ErrSourceContractTelemetry
	}
	at, err := time.Parse(SourceContractTime, value.(string))
	if err != nil || at.Unix() < 0 {
		return time.Time{}, ErrSourceContractTelemetry
	}
	return at, nil
}

// NewSourceContractTelemetry refuses omitted evidence, including nil events.
func NewSourceContractTelemetry(id string, version uint64, epoch string, sequence uint64, observed time.Time, total uint64, events []SourceFailureEvent) (SourceContractSnapshot, error) {
	if observed.Nanosecond()%1000 != 0 {
		return SourceContractSnapshot{}, ErrSourceContractTelemetry
	}
	raw, err := json.Marshal(SourceContractSnapshot{SchemaVersion: SourceContractSchema, SourceID: id, ConfigVersion: version, Epoch: epoch, Sequence: sequence, ObservedAt: observed.UTC().Format(SourceContractTime), WindowStart: observed.UTC().Add(-600 * time.Second).Format(SourceContractTime), TotalFailures: total, Events: events})
	if err != nil {
		return SourceContractSnapshot{}, ErrSourceContractTelemetry
	}
	return DecodeSourceContractTelemetry(raw)
}
