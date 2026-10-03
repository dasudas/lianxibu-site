package outbox

import (
	"context"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"path/filepath"
	"testing"
	"time"
)

func TestRuntimeCompletionAndControlsSurviveRestart(t *testing.T) {
	ctx := context.Background()
	now := fixtureClock()
	path := filepath.Join(t.TempDir(), "runtime.db")
	s, err := Open(path, "node", func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	if err = s.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	claims, err := s.ActiveClaims(ctx)
	if err != nil || len(claims) != 0 {
		t.Fatal("unacknowledged lease runnable", err)
	}
	ackCurrentClaim(t, s)
	_, rev, _ := s.Run(ctx, "run-1")
	if err = s.SaveFetchedPage(ctx, "run-1", "token-a", rev, "", "finished", []Record{fixtureRecord("1")}, true, []string{"missing_optional_document"}); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s, err = Open(path, "node", func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	claims, err = s.ActiveClaims(ctx)
	if err != nil || len(claims) != 1 {
		t.Fatal("live lease not restored", err)
	}
	state, rev, _ := s.Run(ctx, "run-1")
	if !state.FetchComplete || len(state.CoverageGaps) != 1 {
		t.Fatal("lost completion")
	}
	if err = s.SavePage(ctx, "run-1", "token-a", rev, "finished", "changed", nil); err == nil {
		t.Fatal("completed run was rewritten")
	}
	for i := 0; i < 2; i++ {
		if err = s.StageCompletedRun(ctx, claims[0], 3); err != nil {
			t.Fatal(err)
		}
	}
	part, err := s.NextControlAttempt(ctx)
	if err != nil || part == nil || part.Kind != "manifest" {
		t.Fatal("missing manifest", err)
	}
	result := client.ControlResult{RunID: &part.RunID, BusinessHash: part.BusinessHash, ReceiptRef: "fixture-manifest"}
	if ok, e := s.ApplyControlReceipt(ctx, *part, result); e != nil || !ok {
		t.Fatal(e)
	}
	if err = s.StageCompletedRun(ctx, claims[0], 4); err != nil {
		t.Fatal("acknowledged part not reusable", err)
	}
	final, err := s.NextControlAttempt(ctx)
	if err != nil || final == nil || final.Kind != "run_result" {
		t.Fatal("missing result", err)
	}
	value, e := client.ParseStrict(final.Wire)
	if e != nil {
		t.Fatal(e)
	}
	if value.(map[string]any)["business"].(map[string]any)["fetch_outcome"] != "partial" {
		t.Fatal("coverage loss")
	}
	now = now.Add(2 * time.Hour)
	claims, err = s.ActiveClaims(ctx)
	if err != nil || len(claims) != 0 {
		t.Fatal("expired lease runnable")
	}
}
