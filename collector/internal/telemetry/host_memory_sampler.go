package telemetry

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/google/uuid"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var ErrHostMemorySample = errors.New("native collector host memory unavailable")
var nativeBoot = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type hostMemoryFacts struct {
	process string
	pid     uint64
	rss     uint64
	cgroup  string
	maximum *uint64
	oom     uint64
	kills   uint64
}

func hostHash(raw string) string { v := sha256.Sum256([]byte(raw)); return hex.EncodeToString(v[:]) }
func nativeNumber(raw string) (uint64, error) {
	if !regexp.MustCompile(`^(?:0|[1-9][0-9]*)$`).MatchString(raw) {
		return 0, ErrHostMemorySample
	}
	v, err := strconv.ParseUint(raw, 10, 64)
	if err != nil || v > maxSafe {
		return 0, ErrHostMemorySample
	}
	return v, nil
}
func processIdentity(raw []byte, boot string, pid uint64) (string, error) {
	if !nativeBoot.MatchString(boot) || pid == 0 || pid > maxSafe || len(raw) == 0 || len(raw) > 65536 {
		return "", ErrHostMemorySample
	}
	text := strings.TrimSpace(string(raw))
	left := strings.IndexByte(text, '(')
	right := strings.LastIndexByte(text, ')')
	if left < 1 || right <= left {
		return "", ErrHostMemorySample
	}
	id, err := nativeNumber(strings.TrimSpace(text[:left]))
	tail := strings.Fields(text[right+1:])
	if err != nil || id != pid || len(tail) < 20 || len(tail[0]) != 1 {
		return "", ErrHostMemorySample
	}
	ticks, err := nativeNumber(tail[19])
	if err != nil || ticks == 0 {
		return "", ErrHostMemorySample
	}
	return hostHash(boot + "\n" + strconv.FormatUint(pid, 10) + "\n" + tail[19]), nil
}
func nativeRSS(raw []byte, page uint64) (uint64, error) {
	if page == 0 || len(raw) == 0 || len(raw) > 4096 {
		return 0, ErrHostMemorySample
	}
	fields := strings.Fields(string(raw))
	if len(fields) != 7 {
		return 0, ErrHostMemorySample
	}
	pages, err := nativeNumber(fields[1])
	if err != nil || pages == 0 || pages > maxSafe/page {
		return 0, ErrHostMemorySample
	}
	return pages * page, nil
}
func nativeMaximum(raw []byte) (*uint64, error) {
	if len(raw) == 0 || len(raw) > 128 {
		return nil, ErrHostMemorySample
	}
	text := strings.TrimSpace(string(raw))
	if text == "max" {
		return nil, nil
	}
	v, err := nativeNumber(text)
	if err != nil || v == 0 {
		return nil, ErrHostMemorySample
	}
	return &v, nil
}
func nativeEvents(raw []byte) (uint64, uint64, error) {
	if len(raw) == 0 || len(raw) > 4096 {
		return 0, 0, ErrHostMemorySample
	}
	values := map[string]uint64{}
	for _, line := range strings.Split(strings.TrimSpace(string(raw)), "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			return 0, 0, ErrHostMemorySample
		}
		if _, found := values[fields[0]]; found {
			return 0, 0, ErrHostMemorySample
		}
		v, err := nativeNumber(fields[1])
		if err != nil {
			return 0, 0, ErrHostMemorySample
		}
		values[fields[0]] = v
	}
	oom, ok := values["oom"]
	kills, ok2 := values["oom_kill"]
	if !ok || !ok2 {
		return 0, 0, ErrHostMemorySample
	}
	return oom, kills, nil
}
func nativeCgroupPath(raw []byte) (string, error) {
	if len(raw) == 0 || len(raw) > 65536 {
		return "", ErrHostMemorySample
	}
	found := ""
	for _, line := range strings.Split(strings.TrimSpace(string(raw)), "\n") {
		parts := strings.SplitN(line, ":", 3)
		if len(parts) != 3 {
			return "", ErrHostMemorySample
		}
		if parts[0] == "0" && parts[1] == "" {
			if found != "" || !strings.HasPrefix(parts[2], "/") || strings.ContainsAny(parts[2], "\x00\r\n\\") {
				return "", ErrHostMemorySample
			}
			for _, segment := range strings.Split(parts[2], "/") {
				if segment == "." || segment == ".." {
					return "", ErrHostMemorySample
				}
			}
			found = parts[2]
		}
	}
	if found == "" {
		return "", ErrHostMemorySample
	}
	return found, nil
}

type HostMemorySampler struct {
	mu       sync.Mutex
	read     func(context.Context) (hostMemoryFacts, error)
	now      func() time.Time
	elapsed  func() time.Duration
	epoch    string
	started  time.Time
	sequence uint64
	last     time.Time
	process  string
	pid      uint64
	poisoned bool
}

// This registration has its own epoch; it never estimates OS birth from USER_HZ.
func NewHostMemorySampler() (*HostMemorySampler, error) {
	anchor := time.Now()
	return newHostMemorySampler(readNativeHostMemory, time.Now, func() time.Duration { return time.Since(anchor) }, anchor.UTC().Truncate(time.Microsecond))
}
func newHostMemorySampler(read func(context.Context) (hostMemoryFacts, error), now func() time.Time, elapsed func() time.Duration, started time.Time) (*HostMemorySampler, error) {
	if read == nil || now == nil || elapsed == nil || started.Unix() < 0 || started.Nanosecond()%1000 != 0 {
		return nil, ErrHostMemorySample
	}
	id, err := uuid.NewRandom()
	if err != nil {
		return nil, ErrHostMemorySample
	}
	return &HostMemorySampler{read: read, now: now, elapsed: elapsed, epoch: id.String(), started: started}, nil
}
func (s *HostMemorySampler) SampleHostMemory(ctx context.Context) (client.HostMemorySnapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	invalid := func() (client.HostMemorySnapshot, error) { return client.HostMemorySnapshot{}, ErrHostMemorySample }
	if ctx == nil || ctx.Err() != nil || s.poisoned || s.sequence >= maxSafe {
		return invalid()
	}
	before := s.now().UTC().Truncate(time.Microsecond)
	nativeBefore := s.elapsed()
	facts, err := s.read(ctx)
	observed := s.now().UTC().Truncate(time.Microsecond)
	nativeAfter := s.elapsed()
	drift := observed.Sub(s.started) - nativeAfter
	if drift < 0 {
		drift = -drift
	}
	if before.Before(s.started) || observed.Before(before) || nativeBefore < 0 || nativeAfter < nativeBefore || nativeAfter-nativeBefore > 10*time.Second || drift > 2*time.Second || !s.last.IsZero() && !observed.After(s.last) {
		s.poisoned = true
		return invalid()
	}
	if err != nil || ctx.Err() != nil {
		return invalid()
	}
	if s.process != "" && (facts.process != s.process || facts.pid != s.pid) {
		s.poisoned = true
		return invalid()
	}
	snapshot, err := client.NewHostMemoryTelemetry(client.HostMemorySnapshot{SchemaVersion: client.HostMemorySchema, CaptureEpoch: s.epoch, ProcessIdentity: facts.process, CaptureStartedAt: s.started.Format(client.SourceContractTime), Sequence: s.sequence + 1, PID: facts.pid, ObservedAt: observed.Format(client.SourceContractTime), RSSBytes: facts.rss, CgroupEpoch: facts.cgroup, MaximumBytes: facts.maximum, OOMEvents: facts.oom, OOMKills: facts.kills})
	if err != nil {
		return invalid()
	}
	s.sequence++
	s.last = observed
	s.process = facts.process
	s.pid = facts.pid
	return snapshot, nil
}
func (*HostMemorySampler) String() string { return "HostMemorySampler[redacted]" }
