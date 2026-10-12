//go:build !linux && !windows

package telemetry

import "context"

func readNativeHostMemory(context.Context) (hostMemoryFacts, error) {
	return hostMemoryFacts{}, ErrHostMemorySample
}
