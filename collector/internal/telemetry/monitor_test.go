package telemetry

import (
	"context"
	"errors"
	"testing"
	"time"
)

type fakeSampler struct {
	sample Sample
	err    error
}

func (s *fakeSampler) Sample(context.Context) (Sample, error) { return s.sample, s.err }

type fakeSender struct {
	beat  Heartbeat
	calls int
	err   error
}

func (s *fakeSender) SendHeartbeat(_ context.Context, b Heartbeat) error {
	s.beat = b
	s.calls++
	return s.err
}

func TestDiskProtectionWarningAndTenMinuteRecovery(t *testing.T) {
	now := time.Date(2026, 9, 30, 8, 0, 0, 0, time.UTC)
	s := &fakeSampler{sample: Sample{PendingObservations: 2, PendingControls: 1, OutboxBytes: 64 << 20, FreeBytes: 2 << 30}}
	sender := &fakeSender{}
	m, err := NewMonitor(s, sender, func() time.Time { return now }, DefaultLimits())
	if err != nil {
		t.Fatal(err)
	}
	if err := m.SetIdentity("collector-v1.0.0", "0.11", 1); err != nil {
		t.Fatal(err)
	}
	state, err := m.Tick(context.Background())
	if err != nil || state.StopNewInput || sender.beat.PendingObservations != 2 || sender.beat.PendingControls != 1 {
		t.Fatal("healthy startup rejected", err)
	}
	if state.CollectorVersion != "collector-v1.0.0" || state.ProtocolVersion != "0.11" || state.ConfigRevision != 1 {
		t.Fatal("runtime identity lost")
	}
	if err := m.AllowNewInput(); err != nil {
		t.Fatal("healthy input blocked", err)
	}
	s.sample.OutboxBytes = 256 << 20
	now = now.Add(time.Minute)
	state, err = m.Tick(context.Background())
	if err != nil || state.Level == "L2" || state.StopNewInput {
		t.Fatal("warning triggered before full window", err)
	}
	now = now.Add(5 * time.Minute)
	state, err = m.Tick(context.Background())
	if err != nil || state.Level != "L2" || state.StopNewInput {
		t.Fatal("warning window ignored", err)
	}
	if state.Uptime < 5*time.Minute {
		t.Fatal("uptime not sampled")
	}
	s.sample.OutboxBytes = 384 << 20
	state, err = m.Tick(context.Background())
	if err != nil || state.Level != "L1" || !state.StopNewInput {
		t.Fatal("hard queue limit did not stop input", err)
	}
	if !errors.Is(m.AllowNewInput(), ErrNewInputPaused) {
		t.Fatal("hard limit allowed new input")
	}
	s.sample.OutboxBytes = 128 << 20
	s.sample.FreeBytes = 1536 << 20
	now = now.Add(time.Minute)
	state, err = m.Tick(context.Background())
	if err != nil || !state.StopNewInput {
		t.Fatal("protection cleared too early", err)
	}
	now = now.Add(10 * time.Minute)
	state, err = m.Tick(context.Background())
	if err != nil || state.StopNewInput || state.Level != "normal" || s.sample.PendingObservations != 2 {
		t.Fatal("recovery or queue preservation failed", err)
	}
	if err := m.AllowNewInput(); err != nil {
		t.Fatal("recovered input still blocked", err)
	}
}

func TestMissingSampleAndFailedHeartbeatRemainDiagnosable(t *testing.T) {
	now := time.Date(2026, 9, 30, 8, 0, 0, 0, time.UTC)
	s := &fakeSampler{sample: Sample{OutboxBytes: 1, FreeBytes: 2 << 30}}
	sender := &fakeSender{}
	m, _ := NewMonitor(s, sender, func() time.Time { return now }, DefaultLimits())
	if _, err := m.Tick(context.Background()); err != nil {
		t.Fatal(err)
	}
	s.err = errors.New("disk probe unavailable")
	now = now.Add(11 * time.Minute)
	state, err := m.Tick(context.Background())
	if err == nil || !state.StopNewInput || !state.TelemetryUnknown || state.LastErrorCode != "sample_unavailable" {
		t.Fatal("missing sample treated as healthy", err)
	}
	s.err = nil
	sender.err = errors.New("server unavailable")
	now = now.Add(time.Minute)
	state, err = m.Tick(context.Background())
	if err == nil || !state.StopNewInput || !state.ReportFailed || state.LastErrorCode != "heartbeat_delivery_failed" {
		t.Fatal("report failure erased protection", err)
	}
	sender.err = nil
	now = now.Add(10 * time.Minute)
	state, err = m.Tick(context.Background())
	if err != nil || state.StopNewInput || state.ReportFailed || state.LastErrorCode != "" {
		t.Fatal("valid recovery failed", err)
	}
}
