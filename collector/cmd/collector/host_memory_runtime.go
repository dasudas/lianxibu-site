package main

import (
	"context"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/telemetry"
	"log"
	"sync"
	"time"
)

// Production construction always chooses the concrete Linux native sampler and original v3 Store.
func startHostMemory(ctx context.Context, flag string, queue *outbox.Store, api *client.Client) (func(), error) {
	if flag == "" || flag == "false" {
		return func() {}, nil
	}
	if flag != "true" {
		return nil, errors.New("collector host memory mode invalid")
	}
	if ctx == nil || ctx.Err() != nil || queue == nil || api == nil {
		return nil, errors.New("collector host memory dependencies unavailable")
	}
	sampler, err := telemetry.NewHostMemorySampler()
	if err != nil {
		return nil, err
	}
	publisher, err := outbox.NewHostMemoryPublisher(queue, api, sampler)
	if err != nil {
		return nil, err
	}
	child, cancel := context.WithCancel(ctx)
	publisher.Start()
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer publisher.Stop()
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		lastReport := time.Time{}
		for {
			select {
			case <-child.Done():
				return
			case <-ticker.C:
				if _, err := publisher.Tick(child); err != nil && child.Err() == nil && (lastReport.IsZero() || time.Since(lastReport) >= time.Minute) {
					lastReport = time.Now()
					log.Println("collector memory sample deferred; original pending retained")
				}
			}
		}
	}()
	var once sync.Once
	stop := func() { once.Do(func() { cancel(); publisher.Stop(); <-done }) }
	return stop, nil
}
