package parsers

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var workflowSeeds = map[string]string{"github/spec-kit": "spec-driven.md", "Fission-AI/OpenSpec": "docs/workflows.md", "bmad-code-org/BMAD-METHOD": "docs/start/build-your-first-change.md", "obra/superpowers": "docs/README.opencode.md"}

func workflowClaim(repo string) client.RunClaim {
	c := agentClaim(repo)
	c.Config.Config.ParserVersion = "github-workflow-v1"
	return c
}
func TestFourWorkflowRepositoriesAggregatePinnedDocuments(t *testing.T) {
	for repo, path := range workflowSeeds {
		t.Run(repo, func(t *testing.T) {
			c := workflowClaim(repo)
			a := Workflows()
			page := agentPage(t, a, c, "", fmt.Sprintf(`{"id":42,"full_name":%q,"name":"Fictional Workflow","default_branch":"main"}`, repo), 200)
			page = agentPage(t, a, c, page.NextCursor, `[{"draft":false,"prerelease":false,"tag_name":"v1.2.3","published_at":"2026-09-01T12:00:00Z"}]`, 200)
			page = agentPage(t, a, c, page.NextCursor, fmt.Sprintf(`{"sha":%q,"commit":{"committer":{"date":"2026-09-01T12:00:00Z"}}}`, strings.Repeat("a", 40)), 200)
			page = agentPage(t, a, c, page.NextCursor, `{"message":"Not Found"}`, 404)
			page = agentPage(t, a, c, page.NextCursor, fmt.Sprintf(`{"type":"file","path":"README.md","encoding":"base64","content":%q}`, base64.StdEncoding.EncodeToString([]byte("# Fictional README"))), 200)
			if page.End || len(page.Items) != 0 {
				t.Fatal("README emitted incomplete entity")
			}
			page = agentPage(t, Workflows(), c, page.NextCursor, fmt.Sprintf(`{"type":"file","path":%q,"encoding":"base64","content":%q}`, path, base64.StdEncoding.EncodeToString([]byte("# Fictional workflow\nNever execute `rm-all`."))), 200)
			if !page.End || len(page.Items) != 1 || page.Items[0].SourceItemID != "42" {
				t.Fatal("one repo became multiple members")
			}
			var p map[string]any
			json.Unmarshal(page.Items[0].Payload, &p)
			text := p["document_text"].(string)
			if p["category"] != "workflow" || !strings.Contains(text, "Fictional README") || !strings.Contains(text, "Fictional workflow") {
				t.Fatal("aggregation lost")
			}
			found := false
			for _, ref := range p["evidence"].([]any) {
				r := ref.(map[string]any)
				if r["path"] == path {
					found = r["revision"] == strings.Repeat("a", 40) && len(r["file_hash"].(string)) == 64
				}
			}
			if !found {
				t.Fatal("extra evidence missing")
			}
			if _, err := Workflows().Freeze(c, page.Items[0], time.Now()); err != nil {
				t.Fatal(err)
			}
		})
	}
}

type workflowFixtureFetch struct {
	requests []string
	missing  bool
	wrong    bool
}

func (f *workflowFixtureFetch) Fetch(_ context.Context, r fetcher.Request) (fetcher.Result, error) {
	f.requests = append(f.requests, r.URL)
	body := `{"id":42,"full_name":"github/spec-kit","name":"Fictional Workflow","default_branch":"main"}`
	status := 200
	switch {
	case strings.Contains(r.URL, "/releases?"):
		body = `[{"draft":false,"prerelease":false,"tag_name":"v1","published_at":"2026-09-01T12:00:00Z"}]`
	case strings.Contains(r.URL, "/commits/"):
		body = fmt.Sprintf(`{"sha":%q,"commit":{"committer":{"date":"2026-09-01T12:00:00Z"}}}`, strings.Repeat("a", 40))
	case strings.Contains(r.URL, "/license?"):
		body = `{"message":"Not Found"}`
		status = 404
	case strings.Contains(r.URL, "/readme?"):
		body = fmt.Sprintf(`{"type":"file","path":"README.md","encoding":"base64","content":%q}`, base64.StdEncoding.EncodeToString([]byte("# README\n"+strings.Repeat("Fictional ", 3000))))
	case strings.Contains(r.URL, "/contents/"):
		if f.missing {
			body = `{"message":"Not Found"}`
			status = 404
		} else {
			path := "spec-driven.md"
			if f.wrong {
				path = "other.md"
			}
			body = fmt.Sprintf(`{"type":"file","path":%q,"encoding":"base64","content":%q}`, path, base64.StdEncoding.EncodeToString([]byte("# Additional workflow")))
		}
	}
	return fetcher.Result{StatusCode: status, FinalURL: r.URL, Body: []byte(body)}, nil
}
func TestWorkflowSQLiteRetainsAggregateAndMissingDocumentGap(t *testing.T) {
	for _, missing := range []bool{false, true} {
		t.Run(fmt.Sprint(missing), func(t *testing.T) {
			c := workflowClaim("github/spec-kit")
			now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
			path := filepath.Join(t.TempDir(), "workflow.db")
			store, err := outbox.Open(path, "fictional-node", now)
			if err != nil {
				t.Fatal(err)
			}
			defer func() { store.Close() }()
			if err = store.PersistClaim(c); err != nil {
				t.Fatal(err)
			}
			fetch := &workflowFixtureFetch{missing: missing}
			run, _ := scheduler.New(fetch, store, Workflows(), now, 5, time.Minute)
			first, err := run.Run(context.Background(), c)
			if err != nil || first.Finished || first.Observations != 0 || len(first.Cursor) < 16000 {
				t.Fatal("aggregate checkpoint", first, err)
			}
			store.Close()
			store, err = outbox.Open(path, "fictional-node", now)
			if err != nil {
				t.Fatal(err)
			}
			run, _ = scheduler.New(fetch, store, Workflows(), now, 10, time.Minute)
			out, err := run.Run(context.Background(), c)
			count, _ := store.ObservationCount(context.Background(), c.Run.RunID)
			if err != nil || !out.Finished || count != 1 || len(fetch.requests) != 6 {
				t.Fatal("restart lost material", out, err)
			}
			if missing && !strings.Contains(strings.Join(out.CoverageGaps, " "), "missing_workflow_document") {
				t.Fatal("missing doc hidden")
			}
			run, _ = scheduler.New(fetch, store, Workflows(), now, 10, time.Minute)
			if _, err = run.Run(context.Background(), c); err != nil || len(fetch.requests) != 6 {
				t.Fatal("terminal refetch", err)
			}
		})
	}
}

func TestWorkflowRejectsUnexpectedFileWithoutPartialObservation(t *testing.T) {
	c := workflowClaim("github/spec-kit")
	now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
	store, err := outbox.Open(filepath.Join(t.TempDir(), "wrong.db"), "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err = store.PersistClaim(c); err != nil {
		t.Fatal(err)
	}
	fetch := &workflowFixtureFetch{wrong: true}
	run, _ := scheduler.New(fetch, store, Workflows(), now, 10, time.Minute)
	if _, err = run.Run(context.Background(), c); err == nil {
		t.Fatal("unexpected doc accepted")
	}
	count, _ := store.ObservationCount(context.Background(), c.Run.RunID)
	if count != 0 {
		t.Fatal("partial entity counted")
	}
	bad := workflowClaim("fictional/unapproved")
	if _, err := Workflows().Request(bad, ""); err == nil {
		t.Fatal("unapproved workflow repo")
	}
}
