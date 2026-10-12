package client

import (
	"encoding/json"
	"errors"
)

const HostMemorySchema = "collector-host-memory-v1"

var ErrHostMemoryTelemetry = errors.New("invalid collector host memory telemetry")

type HostMemorySnapshot struct {
	SchemaVersion    string  `json:"schema_version"`
	CaptureEpoch     string  `json:"capture_epoch"`
	ProcessIdentity  string  `json:"process_identity"`
	CaptureStartedAt string  `json:"capture_started_at"`
	Sequence         uint64  `json:"sequence"`
	PID              uint64  `json:"pid"`
	ObservedAt       string  `json:"observed_at"`
	RSSBytes         uint64  `json:"rss_bytes"`
	CgroupEpoch      string  `json:"cgroup_epoch"`
	MaximumBytes     *uint64 `json:"maximum_bytes"`
	OOMEvents        uint64  `json:"oom_events"`
	OOMKills         uint64  `json:"oom_kills"`
	PayloadHash      string  `json:"-"`
	wireBytes        []byte
}

func (HostMemorySnapshot) String() string            { return "HostMemorySnapshot[redacted]" }
func (s HostMemorySnapshot) FrozenWireBytes() []byte { return append([]byte(nil), s.wireBytes...) }
func DecodeHostMemoryTelemetry(raw []byte) (HostMemorySnapshot, error) {
	invalid := func() (HostMemorySnapshot, error) { return HostMemorySnapshot{}, ErrHostMemoryTelemetry }
	if len(raw) == 0 || len(raw) > 8192 {
		return invalid()
	}
	value, err := ParseStrict(raw)
	if err != nil {
		return invalid()
	}
	m, err := object(value, "schema_version", "capture_epoch", "process_identity", "capture_started_at", "sequence", "pid", "observed_at", "rss_bytes", "cgroup_epoch", "maximum_bytes", "oom_events", "oom_kills")
	if err != nil || m["schema_version"] != HostMemorySchema {
		return invalid()
	}
	epoch, ok := m["capture_epoch"].(string)
	if !ok || !sourceEpoch.MatchString(epoch) {
		return invalid()
	}
	process, err := wireHash(m["process_identity"])
	if err != nil {
		return invalid()
	}
	cgroup, err := wireHash(m["cgroup_epoch"])
	if err != nil {
		return invalid()
	}
	started, err := sourceContractInstant(m["capture_started_at"])
	if err != nil {
		return invalid()
	}
	observed, err := sourceContractInstant(m["observed_at"])
	if err != nil || started.After(observed) {
		return invalid()
	}
	sequence, err := wireInteger(m["sequence"])
	if err != nil || sequence == 0 {
		return invalid()
	}
	pid, err := wireInteger(m["pid"])
	if err != nil || pid == 0 {
		return invalid()
	}
	rss, err := wireInteger(m["rss_bytes"])
	if err != nil || rss == 0 {
		return invalid()
	}
	oom, err := wireInteger(m["oom_events"])
	if err != nil {
		return invalid()
	}
	kills, err := wireInteger(m["oom_kills"])
	if err != nil {
		return invalid()
	}
	var maximum *uint64
	if m["maximum_bytes"] != nil {
		v, err := wireInteger(m["maximum_bytes"])
		if err != nil || v == 0 {
			return invalid()
		}
		maximum = &v
	}
	hash, err := Hash(ActionHash, value)
	if err != nil {
		return invalid()
	}
	canonical, err := CanonicalBytes(value)
	if err != nil {
		return invalid()
	}
	return HostMemorySnapshot{SchemaVersion: HostMemorySchema, CaptureEpoch: epoch, ProcessIdentity: process, CaptureStartedAt: started.Format(SourceContractTime), Sequence: sequence, PID: pid, ObservedAt: observed.Format(SourceContractTime), RSSBytes: rss, CgroupEpoch: cgroup, MaximumBytes: maximum, OOMEvents: oom, OOMKills: kills, PayloadHash: hash, wireBytes: canonical}, nil
}
func NewHostMemoryTelemetry(snapshot HostMemorySnapshot) (HostMemorySnapshot, error) {
	raw, err := json.Marshal(snapshot)
	if err != nil {
		return HostMemorySnapshot{}, ErrHostMemoryTelemetry
	}
	return DecodeHostMemoryTelemetry(raw)
}
