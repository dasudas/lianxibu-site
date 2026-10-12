package main

import (
	"context"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestHostMemoryRuntimeDefaultFalseNeverRequiresNativeDependencies(t *testing.T) {
	for _, flag := range []string{"", "false"} {
		stop, err := startHostMemory(nil, flag, nil, nil)
		if err != nil || stop == nil {
			t.Fatal("default cold", err)
		}
		stop()
		stop()
	}
	for _, flag := range []string{"TRUE", "yes", " true "} {
		if _, err := startHostMemory(context.Background(), flag, nil, nil); err == nil {
			t.Fatal("unknown private flag accepted")
		}
	}
}
func TestHostMemoryRuntimeEnabledRequiresActualQueueClientAndLiveContext(t *testing.T) {
	if _, err := startHostMemory(context.Background(), "true", nil, nil); err == nil {
		t.Fatal("missing dependencies")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := startHostMemory(ctx, "true", nil, nil); err == nil {
		t.Fatal("dead context")
	}
}
func TestHostMemoryRuntimeConcreteFactoryStartsColdAndStopDrainsIndependentLoop(t *testing.T) {
	ctx := context.Background()
	queue, err := outbox.Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-runtime-node", time.Now)
	if err != nil {
		t.Fatal(err)
	}
	defer queue.Close()
	var calls atomic.Int64
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.WriteHeader(503) }))
	defer server.Close()
	api, err := client.NewClient(server.URL, "fictional-token", server.Client(), time.Now)
	if err != nil {
		t.Fatal(err)
	}
	stop, err := startHostMemory(ctx, "true", queue, api)
	if err != nil {
		t.Fatal("concrete factory", err)
	}
	done := make(chan struct{})
	go func() { stop(); stop(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("native loop did not drain")
	}
	pending, err := queue.PendingHostMemorySnapshot(ctx)
	if err != nil || pending != nil || calls.Load() != 0 {
		t.Fatal("startup fabricated sample or network", err)
	}
}
