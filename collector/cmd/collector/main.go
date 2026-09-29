package main

import (
	"context"
	"database/sql"
	"log"
	"os"
	"os/signal"
	"slices"
	"syscall"

	"github.com/dasudas/lianxibu-site/collector/internal/config"
	_ "modernc.org/sqlite"
)

func main() {
	if _, err := config.Load(os.Getenv); err != nil {
		log.Fatalf("collector configuration invalid: %v", err)
	}
	if !slices.Contains(sql.Drivers(), "sqlite") {
		log.Fatal("SQLite driver unavailable")
	}
	log.Println("collector bootstrap ready; fetching disabled")
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
}
