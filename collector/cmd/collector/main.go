package main

import (
	"context"
	"errors"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/config"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/parsers"
	"github.com/dasudas/lianxibu-site/collector/internal/runner"
	"github.com/dasudas/lianxibu-site/collector/internal/telemetry"
)

func main() {
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		log.Fatalf("collector configuration invalid: %v", err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if os.Getenv("COLLECTOR_ENABLED") != "true" {
		log.Println("collector ready; collection disabled")
		<-ctx.Done()
		return
	}
	if err := run(ctx, cfg); err != nil && !errors.Is(err, context.Canceled) {
		log.Fatal("collector runtime stopped; inspect controlled host configuration and queue")
	}
}

func run(ctx context.Context, cfg config.Config) error {
	queue, err := outbox.Open(cfg.SQLitePath, cfg.NodeID, time.Now)
	if err != nil {
		return err
	}
	defer queue.Close()
	api, err := client.NewClient(cfg.SiteBaseURL, cfg.Token, nil, time.Now)
	if err != nil {
		return err
	}
	sampler, err := telemetry.NewSQLiteSampler(cfg.SQLitePath)
	if err != nil {
		return err
	}
	defer sampler.Close()
	sender, err := telemetry.NewHTTPSender(cfg.SiteBaseURL, cfg.Token, nil)
	if err != nil {
		return err
	}
	monitor, err := telemetry.NewMonitor(sampler, sender, time.Now, telemetry.DefaultLimits())
	if err != nil {
		return err
	}
	if err = monitor.SetIdentity("runtime-v1", "0.11", 0); err != nil {
		return err
	}
	sourceFetcher, err := fetcher.NewPersistent(cfg.SQLitePath + ".host-budget.db")
	if err != nil {
		return err
	}
	defer sourceFetcher.Close()
	worker, err := runner.New(api, queue, sourceFetcher, monitor, time.Now)
	if err != nil {
		return err
	}
	log.Println("collector runtime started")
	if location := os.Getenv("COLLECTOR_LICENSE_REVIEWS"); location != "" {
		if !filepath.IsAbs(location) {
			return errors.New("license review path invalid")
		}
		info, err := os.Lstat(location)
		if err != nil || !info.Mode().IsRegular() || info.Size() > 65536 {
			return errors.New("license reviews unavailable")
		}
		raw, err := os.ReadFile(location)
		if err != nil {
			return errors.New("license reviews unavailable")
		}
		reviews, err := parsers.ParseLicenseReviews(raw)
		if err != nil {
			return err
		}
		worker.SetLicenseReviews(reviews)
	}
	memoryStop, err := startHostMemory(ctx, os.Getenv("COLLECTOR_HOST_MEMORY_ENABLED"), queue, api)
	if err != nil {
		return err
	}
	defer memoryStop()
	return worker.Run(ctx, func(error) { log.Println("collector cycle deferred; durable work retained") })
}
