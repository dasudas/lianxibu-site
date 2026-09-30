package scheduler

import (
	"context"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"testing"
)

type missingDocumentAdapter struct {
	fixtureAdapter
	parsed *int
}

func (m missingDocumentAdapter) AllowNotFound() bool { return true }
func (m missingDocumentAdapter) Parse(c client.RunClaim, r fetcher.Result) (Page, error) {
	*m.parsed++
	return Page{Items: []Item{}, End: true, NextCursor: "completed", Gaps: []string{"missing_optional_document"}}, nil
}
func TestOptional404IsParsedOnlyByExplicitAdapter(t *testing.T) {
	c := claim("daily_delta")
	for _, status := range []int{404, 403, 500} {
		s, store, _ := setup(c, 1, fetcher.Result{StatusCode: status})
		parsed := 0
		s.adapter = missingDocumentAdapter{parsed: &parsed}
		out, err := s.Run(context.Background(), c)
		if status == 404 {
			if err != nil || parsed != 1 || store.revision != 1 || !out.Finished || len(out.CoverageGaps) != 1 {
				t.Fatal("optional404 not checkpointed", err)
			}
		} else if err == nil || parsed != 0 || store.revision != 0 {
			t.Fatal("other failure interpreted as missing", status)
		}
	}
	s, store, _ := setup(c, 1, fetcher.Result{StatusCode: 404})
	if _, err := s.Run(context.Background(), c); err == nil || store.revision != 0 {
		t.Fatal("ordinary adapter404 accepted")
	}
}
