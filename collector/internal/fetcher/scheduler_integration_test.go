package fetcher_test

import (
	"context"
	"encoding/base64"
	"fmt"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/parsers"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"net/http"
	"strings"
	"testing"
	"time"
)

type checkpoint struct {
	state    outbox.RunState
	revision uint64
	records  []outbox.Record
}

func (s *checkpoint) Run(context.Context, string) (outbox.RunState, uint64, error) {
	return s.state, s.revision, nil
}
func (s *checkpoint) ObservationCount(context.Context, string) (int, error) {
	return len(s.records), nil
}
func (s *checkpoint) HasMember(context.Context, string, string) (bool, error) { return false, nil }
func (s *checkpoint) SavePage(_ context.Context, id, token string, revision uint64, before, after string, records []outbox.Record) error {
	if id != s.state.Frozen.Run.RunID || token != *s.state.Frozen.ClaimToken || revision != s.revision || before != s.state.Cursor {
		return outbox.ErrStaleRun
	}
	s.state.Cursor = after
	s.revision++
	s.records = append(s.records, records...)
	return nil
}
func TestRealTLSFetcherSchedulerAndCatalogMissingDocuments(t *testing.T) {
	for _, scenario := range []struct {
		name                          string
		identityStatus, licenseStatus int
		ok                            bool
		checkpoints                   uint64
	}{
		{"optional404", 200, 404, true, 5}, {"permission403", 200, 403, false, 3}, {"missingIdentity", 404, 404, false, 0},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			f := fetcher.LoopbackFixture(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				switch {
				case r.URL.Path == "/repos/openai/codex":
					w.WriteHeader(scenario.identityStatus)
					fmt.Fprint(w, `{"id":42,"name":"Fictional Agent","full_name":"openai/codex","description":"fictional","default_branch":"main"}`)
				case strings.HasSuffix(r.URL.Path, "/releases"):
					fmt.Fprint(w, `[{"id":10,"draft":false,"prerelease":false,"tag_name":"v1","published_at":"2026-09-01T12:00:00Z"}]`)
				case strings.Contains(r.URL.Path, "/commits/"):
					fmt.Fprintf(w, `{"sha":%q,"commit":{"committer":{"date":"2026-08-31T12:00:00Z"}}}`, strings.Repeat("a", 40))
				case strings.HasSuffix(r.URL.Path, "/license"):
					w.WriteHeader(scenario.licenseStatus)
					fmt.Fprint(w, `{"message":"Not Found"}`)
				case strings.HasSuffix(r.URL.Path, "/readme"):
					fmt.Fprintf(w, `{"type":"file","path":"README.md","encoding":"base64","content":%q}`, base64.StdEncoding.EncodeToString([]byte("# Fictional Agent")))
				default:
					w.WriteHeader(500)
				}
			})
			var c client.RunClaim
			c.Run.RunID = "fictional-run"
			c.Run.BusinessDate = "2026-09-30"
			c.Run.RunKind = "production"
			c.Run.CollectionReason = "daily"
			c.Run.SourceID = "fictional-source"
			c.Run.ConfigVersion = 1
			c.Run.Generation = 1
			c.Run.FetchMode = "daily_delta"
			c.Run.WindowStart = "2026-09-29T00:00:00.000000Z"
			c.Run.WindowEnd = "2026-09-30T00:00:00.000000Z"
			c.Config.SourceID = c.Run.SourceID
			c.Config.ConfigVersion = 1
			c.Config.Config.ParserVersion = "github-agent-v1"
			c.Config.Config.EntryURLs = []string{"https://api.github.com/repos/openai/codex"}
			c.Config.Config.AllowedHosts = []string{"api.github.com"}
			c.Config.Config.MaxItems = 10
			c.Config.Config.MaxResponseBytes = 2097152
			c.Config.Config.ValidUntil = "2027-01-01T00:00:00.000000Z"
			token, until := "fictional-token", "2026-10-01T00:00:00.000000Z"
			c.ClaimToken = &token
			c.LeaseUntil = &until
			c.CurrentLeaseValid = true
			store := &checkpoint{state: outbox.RunState{Frozen: c}}
			now := func() time.Time { return time.Date(2026, 9, 30, 0, 0, 0, 0, time.UTC) }
			runner, err := scheduler.New(f, store, parsers.Agents(), now, 10, time.Minute)
			if err != nil {
				t.Fatal(err)
			}
			result, err := runner.Run(context.Background(), c)
			if store.revision != scenario.checkpoints {
				t.Fatal("unexpected checkpoint count", store.revision, err)
			}
			if scenario.ok {
				if err != nil || !result.Finished || len(store.records) != 1 || !strings.Contains(strings.Join(result.CoverageGaps, ","), "missing_license") {
					t.Fatal(result, err)
				}
			} else if err == nil || len(store.records) != 0 {
				t.Fatal("failure advanced catalog", result, err)
			}
		})
	}
}
