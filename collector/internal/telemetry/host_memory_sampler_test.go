package telemetry

import (
	"context"
	"strings"
	"testing"
	"time"
)

func procFixture(pid string, ticks string) []byte {
	return []byte(pid + " (fictional worker (nested)) S " + strings.Repeat("0 ", 18) + ticks + " 0 0\n")
}
func TestHostMemoryNativeProcessUsesActualRawStartTicksNotAssumedHz(t *testing.T) {
	boot := "00000000-0000-4000-8000-000000000001"
	a, err := processIdentity(procFixture("12", "1000"), boot, 12)
	if err != nil || a != hostHash(boot+"\n12\n1000") {
		t.Fatal("exact native identity", err)
	}
	b, err := processIdentity(procFixture("12", "1001"), boot, 12)
	if err != nil || a == b {
		t.Fatal("process reuse", err)
	}
	for _, raw := range [][]byte{procFixture("13", "1000"), procFixture("12", "0"), procFixture("12", "1000.0"), []byte("12 (broken) S 1 2")} {
		if _, err := processIdentity(raw, boot, 12); err != ErrHostMemorySample {
			t.Fatal("invalid native process")
		}
	}
	if _, err := processIdentity(procFixture("12", "1000"), "missing", 12); err != ErrHostMemorySample {
		t.Fatal("boot identity")
	}
}
func TestHostMemoryNativeRssRejectsOverflowMissingOrInventedZero(t *testing.T) {
	v, err := nativeRSS([]byte("1000 123 0 0 0 0 0\n"), 4096)
	if err != nil || v != 123*4096 {
		t.Fatal("native pages", err)
	}
	for _, raw := range []string{"1 0 0 0 0 0 0", "1 9007199254740991 0 0 0 0 0", "1 1.0 0 0 0 0 0", "1 1", "1 -1 0 0 0 0 0"} {
		if _, err := nativeRSS([]byte(raw), 4096); err != ErrHostMemorySample {
			t.Fatal("rss accepted", raw)
		}
	}
}
func TestHostMemoryNativeMaximumAndLocalOomCountersStayDistinct(t *testing.T) {
	v, err := nativeMaximum([]byte("201326592\n"))
	if err != nil || *v != 192<<20 {
		t.Fatal("actual max", err)
	}
	v, err = nativeMaximum([]byte("max\n"))
	if err != nil || v != nil {
		t.Fatal("native unlimited", err)
	}
	oom, kills, err := nativeEvents([]byte("low 0\nhigh 0\nmax 1\noom 0\noom_kill 2\noom_group_kill 0\n"))
	if err != nil || oom != 0 || kills != 2 {
		t.Fatal("independent local counters", err)
	}
	for _, raw := range []string{"", "oom 0", "oom 0\noom_kill 0\noom 1", "oom -1\noom_kill 0", "oom 0\noom_kill 9007199254740992"} {
		if _, _, err := nativeEvents([]byte(raw)); err != ErrHostMemorySample {
			t.Fatal("missing or damaged counters", raw)
		}
	}
	for _, raw := range []string{"", "0", "-1", "1.0", "MAX"} {
		if _, err := nativeMaximum([]byte(raw)); err != ErrHostMemorySample {
			t.Fatal("invented max")
		}
	}
}
func TestHostMemoryNativeCgroupOnlyUsesCompleteUnifiedSafePath(t *testing.T) {
	for _, raw := range []string{"0::/fictional.slice/worker.service\n", "0::/\n"} {
		path, err := nativeCgroupPath([]byte(raw))
		if err != nil || !strings.HasPrefix(path, "/") {
			t.Fatal("native unified", err)
		}
	}
	for _, raw := range []string{"", "1:memory:/fictional", "0::/../fictional", "0::/a/./b", "0::/a\\b", "0::/a\n0::/b", "0::relative"} {
		if _, err := nativeCgroupPath([]byte(raw)); err != ErrHostMemorySample {
			t.Fatal("unsafe or ambiguous cgroup", raw)
		}
	}
}
func memorySamplerFixture(t *testing.T) (*HostMemorySampler, *time.Time, *time.Duration, *hostMemoryFacts) {
	t.Helper()
	at := time.Date(2026, 1, 1, 0, 10, 0, 0, time.UTC)
	elapsed := time.Duration(0)
	maximum := uint64(192 << 20)
	facts := hostMemoryFacts{strings.Repeat("a", 64), 12, 4096, strings.Repeat("b", 64), &maximum, 0, 2}
	s, err := newHostMemorySampler(func(context.Context) (hostMemoryFacts, error) { return facts, nil }, func() time.Time { return at }, func() time.Duration { return elapsed }, at)
	if err != nil {
		t.Fatal(err)
	}
	return s, &at, &elapsed, &facts
}
func TestHostMemoryRegistrationCapturesNativeFactsAndImmutableSequence(t *testing.T) {
	s, at, elapsed, facts := memorySamplerFixture(t)
	first, err := s.SampleHostMemory(context.Background())
	if err != nil || first.Sequence != 1 || first.PID != 12 || first.OOMKills != 2 || first.CaptureStartedAt != at.Format("2006-01-02T15:04:05.000000Z") {
		t.Fatal("actual frame", err)
	}
	*at = at.Add(time.Minute)
	*elapsed = time.Minute
	facts.maximum = nil
	facts.rss = 256 << 20
	second, err := s.SampleHostMemory(context.Background())
	if err != nil || second.Sequence != 2 || second.CaptureEpoch != first.CaptureEpoch || second.MaximumBytes != nil || second.RSSBytes != 256<<20 {
		t.Fatal("actual later frame", err)
	}
	if s.String() != "HostMemorySampler[redacted]" {
		t.Fatal("diagnostic")
	}
	fresh, _, _, _ := memorySamplerFixture(t)
	if fresh.epoch == s.epoch {
		t.Fatal("registration reused epoch")
	}
}
func TestHostMemoryClockDriftRollbackAndProcessChangePoisonRegistration(t *testing.T) {
	for _, change := range []func(*time.Time, *time.Duration, *hostMemoryFacts){func(at *time.Time, elapsed *time.Duration, f *hostMemoryFacts) { *at = at.Add(-time.Second) }, func(at *time.Time, elapsed *time.Duration, f *hostMemoryFacts) {
		*at = at.Add(time.Minute)
		*elapsed = time.Second
	}, func(at *time.Time, elapsed *time.Duration, f *hostMemoryFacts) {
		*at = at.Add(time.Minute)
		*elapsed = time.Minute
		f.process = strings.Repeat("c", 64)
	}} {
		s, at, elapsed, facts := memorySamplerFixture(t)
		if _, err := s.SampleHostMemory(context.Background()); err != nil {
			t.Fatal(err)
		}
		change(at, elapsed, facts)
		if _, err := s.SampleHostMemory(context.Background()); err != ErrHostMemorySample || !s.poisoned {
			t.Fatal("unsafe continuity")
		}
		*at = at.Add(time.Hour)
		*elapsed = time.Hour
		if _, err := s.SampleHostMemory(context.Background()); err != ErrHostMemorySample {
			t.Fatal("poison silently healed")
		}
	}
}
func TestHostMemoryUnavailableOrCancelledSampleCannotInventFrame(t *testing.T) {
	s, at, elapsed, _ := memorySamplerFixture(t)
	s.read = func(context.Context) (hostMemoryFacts, error) { return hostMemoryFacts{}, ErrHostMemorySample }
	if _, err := s.SampleHostMemory(context.Background()); err != ErrHostMemorySample || s.sequence != 0 {
		t.Fatal("missing native data")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := s.SampleHostMemory(ctx); err != ErrHostMemorySample || s.sequence != 0 {
		t.Fatal("cancelled sample")
	}
	*at = at.Add(time.Minute)
	*elapsed = time.Minute
	s.sequence = maxSafe
	if _, err := s.SampleHostMemory(context.Background()); err != ErrHostMemorySample {
		t.Fatal("unsafe sequence")
	}
}
