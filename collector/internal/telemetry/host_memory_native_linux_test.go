//go:build linux

package telemetry

import (
	"context"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"os"
	"testing"
	"time"
)

// Opt-in execution under an owned 192 MiB cgroup: actual kernel facts, never substituted fixtures.
func TestLinuxHostMemoryKernelQualification(t *testing.T) {
	if os.Getenv("LCM_NATIVE_QUALIFICATION") != "true" {
		t.Skip("owned cgroup native qualification not active")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	native, err := readNativeHostMemory(ctx)
	if err != nil {
		t.Fatal("actual kernel source unavailable", err)
	}
	if native.pid != uint64(os.Getpid()) || native.rss == 0 || native.maximum == nil || *native.maximum != 192<<20 || len(native.process) != 64 || len(native.cgroup) != 64 {
		t.Fatal("owned cgroup or actual process identity not qualified")
	}
	sampler, err := NewHostMemorySampler()
	if err != nil {
		t.Fatal(err)
	}
	first, err := sampler.SampleHostMemory(ctx)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(5 * time.Millisecond)
	next, err := sampler.SampleHostMemory(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if first.Sequence != 1 || next.Sequence != 2 || first.CaptureEpoch != next.CaptureEpoch || first.ProcessIdentity != native.process || next.ProcessIdentity != native.process || next.PID != uint64(os.Getpid()) || first.CgroupEpoch != native.cgroup || next.CgroupEpoch != native.cgroup || next.RSSBytes == 0 || next.MaximumBytes == nil || *next.MaximumBytes != 192<<20 || next.OOMEvents < native.oom || next.OOMKills < native.kills {
		t.Fatal("actual forward native sample failed")
	}
	a, err := time.Parse(client.SourceContractTime, first.ObservedAt)
	if err != nil {
		t.Fatal(err)
	}
	b, err := time.Parse(client.SourceContractTime, next.ObservedAt)
	if err != nil || !b.After(a) {
		t.Fatal("actual native observation clock not forward")
	}
	if _, err = client.DecodeHostMemoryTelemetry(next.FrozenWireBytes()); err != nil {
		t.Fatal("actual native frozen wire invalid", err)
	}
	restarted, err := NewHostMemorySampler()
	if err != nil {
		t.Fatal(err)
	}
	again, err := restarted.SampleHostMemory(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if again.Sequence != 1 || again.CaptureEpoch == first.CaptureEpoch || again.ProcessIdentity != first.ProcessIdentity || again.CgroupEpoch != first.CgroupEpoch {
		t.Fatal("native capture lifetime reset lost actual process/cgroup identity")
	}
	t.Log("actual Linux kernel RSS, cgroup2 memory.max, local OOM counters and forward frozen frame qualified")
}
