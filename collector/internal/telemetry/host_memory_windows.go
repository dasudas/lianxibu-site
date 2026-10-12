package telemetry

import "context"

// Production host-memory evidence requires Linux cgroup v2. Unsupported hosts remain unavailable.
func readNativeHostMemory(context.Context) (hostMemoryFacts, error) {
	return hostMemoryFacts{}, ErrHostMemorySample
}
