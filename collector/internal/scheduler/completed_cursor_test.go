package scheduler

import (
	"context"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"testing"
	"time"
)

type completedFixture struct{ fixtureAdapter }

func (completedFixture) Completed(_ client.RunClaim, cursor string) (bool, []string, error) {
	if cursor == "done" {
		return true, []string{"coverage_unproven"}, nil
	}
	return false, nil, ErrRunChanged
}
func TestCompletedCursorStopsRestartWithoutNetworkAndRejectsCorruption(t *testing.T) {
	c := claim("daily_delta")
	c.Config.Config.MaxItems = 10
	for _, cursor := range []string{"done", "corrupt"} {
		store := &memoryStore{state: outbox.RunState{Frozen: c, Cursor: cursor}}
		fetch := &memoryFetch{}
		run, _ := New(fetch, store, completedFixture{}, clock, 1, time.Minute)
		result, err := run.Run(context.Background(), c)
		if cursor == "done" && (err != nil || !result.Finished || len(result.CoverageGaps) != 1 || result.Pages != 0) {
			t.Fatal("completed restart changed", result, err)
		}
		if cursor == "corrupt" && err == nil {
			t.Fatal("corrupt completion accepted")
		}
		if len(fetch.requests) != 0 {
			t.Fatal("completion recovery issued a network request")
		}
	}
}
