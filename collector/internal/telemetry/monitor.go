package telemetry

import (
	"context"
	"errors"
	"sync"
	"time"
)

const maxSafe = 9007199254740991

var ErrNewInputPaused = errors.New("new collector input paused by telemetry guard")

type Limits struct {
	WarningBytes    uint64
	StopBytes       uint64
	StopFreeBytes   uint64
	ResumeFreeBytes uint64
	WarningFor      time.Duration
	ResumeFor       time.Duration
	UnknownFor      time.Duration
}

func DefaultLimits() Limits {
	return Limits{WarningBytes: 256 << 20, StopBytes: 384 << 20, StopFreeBytes: 1 << 30,
		ResumeFreeBytes: 1536 << 20, WarningFor: 5 * time.Minute, ResumeFor: 10 * time.Minute, UnknownFor: 10 * time.Minute}
}

type Sample struct {
	PendingObservations uint64
	PendingControls     uint64
	Quarantined         uint64
	OutboxBytes         uint64
	FreeBytes           uint64
	RSSBytes            uint64
	OldestPendingAt     *time.Time
}

type Sampler interface {
	Sample(context.Context) (Sample, error)
}
type Sender interface {
	SendHeartbeat(context.Context, Heartbeat) error
}

type Heartbeat struct {
	ObservedAt          string  `json:"observed_at"`
	PendingObservations uint64  `json:"pending_observations"`
	PendingControls     uint64  `json:"pending_controls"`
	Quarantined         uint64  `json:"quarantined"`
	OutboxBytes         uint64  `json:"outbox_bytes"`
	FreeBytes           uint64  `json:"free_bytes"`
	OldestPendingAt     *string `json:"oldest_pending_at"`
}

type State struct {
	Level            string
	StopNewInput     bool
	TelemetryUnknown bool
	SampleValid      bool
	ReportFailed     bool
	LastSampleAt     time.Time
	LastErrorCode    string
	Sample           Sample
	Uptime           time.Duration
	CollectorVersion string
	ProtocolVersion  string
	ConfigRevision   uint64
}

type Monitor struct {
	mu                sync.Mutex
	sampl             Sampler
	sender            Sender
	now               func() time.Time
	limits            Limits
	state             State
	warningSince      time.Time
	resumeSince       time.Time
	lastGood          time.Time
	firstUnknown      time.Time
	unknownProtection bool
	startedAt         time.Time
}

func NewMonitor(s Sampler, sender Sender, now func() time.Time, limits Limits) (*Monitor, error) {
	if s == nil || sender == nil || now == nil || limits.WarningBytes == 0 || limits.StopBytes <= limits.WarningBytes ||
		limits.StopFreeBytes == 0 || limits.ResumeFreeBytes <= limits.StopFreeBytes || limits.WarningFor <= 0 || limits.ResumeFor <= 0 || limits.UnknownFor <= 0 {
		return nil, errors.New("invalid telemetry monitor configuration")
	}
	return &Monitor{sampl: s, sender: sender, now: now, limits: limits, startedAt: now().UTC(), state: State{Level: "telemetry_unknown", StopNewInput: true, TelemetryUnknown: true, LastErrorCode: "not_sampled"}}, nil
}

func (m *Monitor) SetIdentity(collectorVersion, protocolVersion string, configRevision uint64) error {
	if collectorVersion == "" || protocolVersion == "" || configRevision > maxSafe {
		return errors.New("invalid telemetry identity")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.state.CollectorVersion = collectorVersion
	m.state.ProtocolVersion = protocolVersion
	m.state.ConfigRevision = configRevision
	return nil
}

func (m *Monitor) Current() State {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.state
}

func (m *Monitor) AllowNewInput() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.state.StopNewInput || !m.state.SampleValid || m.state.TelemetryUnknown {
		return ErrNewInputPaused
	}
	return nil
}

// Tick samples before reporting. Reporting failure never erases queued work or a stop decision.
func (m *Monitor) Tick(ctx context.Context) (State, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now().UTC()
	m.state.Uptime = now.Sub(m.startedAt)
	if m.state.Uptime < 0 {
		m.state.Uptime = 0
	}
	if !m.state.LastSampleAt.IsZero() && now.Before(m.state.LastSampleAt) {
		m.state.Level = "telemetry_unknown"
		m.state.StopNewInput = true
		m.state.TelemetryUnknown = true
		m.state.SampleValid = false
		m.state.LastErrorCode = "clock_rollback"
		return m.state, errors.New("telemetry clock moved backward")
	}
	sample, err := m.sampl.Sample(ctx)
	if err != nil || sample.PendingObservations > maxSafe || sample.PendingControls > maxSafe || sample.Quarantined > maxSafe ||
		sample.OutboxBytes > maxSafe || sample.FreeBytes > maxSafe || sample.RSSBytes > maxSafe {
		m.warningSince = time.Time{}
		m.resumeSince = time.Time{}
		m.state.SampleValid = false
		m.state.TelemetryUnknown = true
		m.state.Level = "telemetry_unknown"
		m.state.LastErrorCode = "sample_unavailable"
		if m.firstUnknown.IsZero() {
			m.firstUnknown = now
		}
		if m.lastGood.IsZero() || now.Sub(m.lastGood) >= m.limits.UnknownFor {
			m.state.StopNewInput = true
			m.unknownProtection = true
		}
		if err == nil {
			err = errors.New("invalid telemetry sample")
		}
		return m.state, err
	}
	firstHealthy := m.lastGood.IsZero() && !m.unknownProtection
	m.firstUnknown = time.Time{}
	m.lastGood = now
	m.state.Sample = sample
	m.state.LastSampleAt = now
	m.state.SampleValid = true
	m.state.TelemetryUnknown = false
	m.state.LastErrorCode = ""
	hard := sample.OutboxBytes >= m.limits.StopBytes || sample.FreeBytes < m.limits.StopFreeBytes
	warning := sample.OutboxBytes >= m.limits.WarningBytes
	if firstHealthy && !hard {
		m.state.StopNewInput = false
	}
	if hard {
		m.state.StopNewInput = true
		m.state.Level = "L1"
		m.warningSince = time.Time{}
		m.resumeSince = time.Time{}
	} else {
		if warning {
			if m.warningSince.IsZero() {
				m.warningSince = now
			}
		} else {
			m.warningSince = time.Time{}
		}
		if m.state.StopNewInput {
			if sample.OutboxBytes < m.limits.WarningBytes && sample.FreeBytes >= m.limits.ResumeFreeBytes {
				if m.resumeSince.IsZero() {
					m.resumeSince = now
				}
				if now.Sub(m.resumeSince) >= m.limits.ResumeFor {
					m.state.StopNewInput = false
					m.unknownProtection = false
					m.resumeSince = time.Time{}
				}
			} else {
				m.resumeSince = time.Time{}
			}
		}
		switch {
		case m.state.StopNewInput:
			m.state.Level = "L1"
		case warning && !m.warningSince.IsZero() && now.Sub(m.warningSince) >= m.limits.WarningFor:
			m.state.Level = "L2"
		default:
			m.state.Level = "normal"
		}
	}
	stamp := func(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000000Z") }
	beat := Heartbeat{ObservedAt: stamp(now), PendingObservations: sample.PendingObservations, PendingControls: sample.PendingControls,
		Quarantined: sample.Quarantined, OutboxBytes: sample.OutboxBytes, FreeBytes: sample.FreeBytes}
	if sample.OldestPendingAt != nil {
		value := stamp(*sample.OldestPendingAt)
		beat.OldestPendingAt = &value
	}
	if err := m.sender.SendHeartbeat(ctx, beat); err != nil {
		m.state.ReportFailed = true
		m.state.LastErrorCode = "heartbeat_delivery_failed"
		return m.state, err
	}
	m.state.ReportFailed = false
	return m.state, nil
}

func (m *Monitor) Run(ctx context.Context, observe func(State, error)) {
	state, err := m.Tick(ctx)
	if observe != nil {
		observe(state, err)
	}
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			state, err = m.Tick(ctx)
			if observe != nil {
				observe(state, err)
			}
		}
	}
}
