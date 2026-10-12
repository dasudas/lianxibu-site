package telemetry

import (
	"context"
	"fmt"
	"golang.org/x/sys/unix"
	"io"
	"os"
	"path/filepath"
	"strings"
)

func nativeRead(path string, limit int64) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, ErrHostMemorySample
	}
	defer f.Close()
	raw, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil || len(raw) == 0 || int64(len(raw)) > limit {
		return nil, ErrHostMemorySample
	}
	return raw, nil
}
func readNativeHostMemory(ctx context.Context) (hostMemoryFacts, error) {
	invalid := func() (hostMemoryFacts, error) { return hostMemoryFacts{}, ErrHostMemorySample }
	if ctx.Err() != nil {
		return invalid()
	}
	boot, err := nativeRead("/proc/sys/kernel/random/boot_id", 128)
	if err != nil {
		return invalid()
	}
	bootID := strings.TrimSpace(string(boot))
	pid := uint64(os.Getpid())
	stat, err := nativeRead("/proc/self/stat", 65536)
	if err != nil {
		return invalid()
	}
	process, err := processIdentity(stat, bootID, pid)
	if err != nil {
		return invalid()
	}
	membership, err := nativeRead("/proc/self/cgroup", 65536)
	if err != nil {
		return invalid()
	}
	path, err := nativeCgroupPath(membership)
	if err != nil {
		return invalid()
	}
	folder := filepath.Clean("/sys/fs/cgroup" + path)
	if folder != "/sys/fs/cgroup" && !strings.HasPrefix(folder, "/sys/fs/cgroup/") {
		return invalid()
	}
	fd, err := unix.Open(folder, unix.O_RDONLY|unix.O_DIRECTORY|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
	if err != nil {
		return invalid()
	}
	defer unix.Close(fd)
	var fs unix.Statfs_t
	var original unix.Stat_t
	if unix.Fstatfs(fd, &fs) != nil || fs.Type != unix.CGROUP2_SUPER_MAGIC || unix.Fstat(fd, &original) != nil {
		return invalid()
	}
	readAt := func(name string, limit int64) ([]byte, error) {
		id, err := unix.Openat(fd, name, unix.O_RDONLY|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
		if err != nil {
			return nil, ErrHostMemorySample
		}
		f := os.NewFile(uintptr(id), "native-cgroup-input")
		defer f.Close()
		raw, err := io.ReadAll(io.LimitReader(f, limit+1))
		if err != nil || len(raw) == 0 || int64(len(raw)) > limit {
			return nil, ErrHostMemorySample
		}
		return raw, nil
	}
	max, err := readAt("memory.max", 128)
	if err != nil {
		return invalid()
	}
	maximum, err := nativeMaximum(max)
	if err != nil {
		return invalid()
	}
	events, err := readAt("memory.events.local", 4096)
	if err != nil {
		return invalid()
	}
	oom, kills, err := nativeEvents(events)
	if err != nil {
		return invalid()
	}
	statm, err := nativeRead("/proc/self/statm", 4096)
	if err != nil {
		return invalid()
	}
	rss, err := nativeRSS(statm, uint64(os.Getpagesize()))
	if err != nil {
		return invalid()
	}
	after, err := nativeRead("/proc/self/stat", 65536)
	if err != nil {
		return invalid()
	}
	same, err := processIdentity(after, bootID, pid)
	if err != nil || same != process {
		return invalid()
	}
	afterGroup, err := nativeRead("/proc/self/cgroup", 65536)
	if err != nil {
		return invalid()
	}
	samePath, err := nativeCgroupPath(afterGroup)
	if err != nil || samePath != path {
		return invalid()
	}
	afterBoot, err := nativeRead("/proc/sys/kernel/random/boot_id", 128)
	if err != nil || strings.TrimSpace(string(afterBoot)) != bootID {
		return invalid()
	}
	var final unix.Stat_t
	if unix.Fstat(fd, &final) != nil || final.Dev != original.Dev || final.Ino != original.Ino || final.Ctim != original.Ctim || ctx.Err() != nil {
		return invalid()
	}
	cgroup := hostHash(fmt.Sprintf("%s\n%d\n%d\n%d\n%d\n%s", bootID, original.Dev, original.Ino, original.Ctim.Sec, original.Ctim.Nsec, path))
	return hostMemoryFacts{process, pid, rss, cgroup, maximum, oom, kills}, nil
}
