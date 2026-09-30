package telemetry

import (
	"context"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
)

func TestSQLiteSamplerReadsRealQueueAndDiskWithoutMutation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "queue.db")
	now := time.Date(2026, 9, 30, 8, 0, 0, 0, time.UTC)
	store, err := outbox.Open(path, "node", func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	claim := client.RunClaim{}
	claim.Run.RunID = "run-1"
	claim.Run.SourceID = "source"
	claim.Run.ConfigVersion = 1
	claim.Run.WindowStart = "2026-09-29T00:00:00.000000Z"
	claim.Run.WindowEnd = "2026-09-30T00:00:00.000000Z"
	claim.Config.SourceID = "source"
	claim.Config.ConfigVersion = 1
	token, until := "token-a", "2026-09-30T09:00:00.000000Z"
	claim.ClaimToken = &token
	claim.LeaseUntil = &until
	claim.CurrentLeaseValid = true
	if err := store.PersistClaim(claim); err != nil {
		t.Fatal(err)
	}
	record := outbox.Record{ID: "row-1", Kind: "observation", RecordKey: strings.Repeat("a", 64), SourceID: "source", RunID: "run-1",
		MemberID: "member-1", ObservationID: "observation-1", Payload: []byte(`{"fictional":true}`), PayloadHash: strings.Repeat("b", 64), ProtocolRevision: "0.11", HashVersion: 1}
	if err := store.SavePage(context.Background(), "run-1", "token-a", 0, "", "end", []outbox.Record{record}); err != nil {
		t.Fatal(err)
	}
	sampler, err := NewSQLiteSampler(path)
	if err != nil {
		t.Fatal(err)
	}
	defer sampler.Close()
	sample, err := sampler.Sample(context.Background())
	if err != nil || sample.PendingObservations != 1 || sample.PendingControls != 1 || sample.Quarantined != 0 || sample.OutboxBytes == 0 || sample.FreeBytes == 0 || sample.RSSBytes == 0 || sample.OldestPendingAt == nil {
		t.Fatal("queue or disk sample invalid", sample, err)
	}
	if n, err := store.ObservationCount(context.Background(), "run-1"); err != nil || n != 1 {
		t.Fatal("sampling changed queue", n, err)
	}
}
