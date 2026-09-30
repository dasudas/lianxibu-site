package parsers

import (
	"context"
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

func skillsbenchClaim() client.RunClaim {
	c := parserClaim()
	c.Run.SourceID = "fictional-skillsbench"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "skillsbench-v1"
	c.Config.Config.EntryURLs = []string{"https://huggingface.co/api/datasets/benchflow/skillsbench-leaderboard?expand%5B%5D=sha&expand%5B%5D=gated&expand%5B%5D=lastModified"}
	c.Config.Config.AllowedHosts = []string{"huggingface.co", "api.github.com"}
	return c
}
func skillsbenchData() []byte {
	rows := []any{}
	for i := 0; i < 17; i++ {
		var score any = json.Number("37.25")
		if i == 1 {
			score = nil
		}
		agent := []string{"codex", "claude-code", "gemini-cli"}[i%3]
		rows = append(rows, map[string]any{"benchmark": "skillsbench", "version": "v1.1", "source_release": "v1.1", "source_commit": strings.Repeat("b", 40), "agent": agent, "model": fmt.Sprintf("Fictional display model %02d", i), "harness": "benchflow", "with_skills_score": score, "without_skills_score": json.Number("25.5"), "num_tasks": 87, "with_selected_trials": 261, "without_selected_trials": 200, "expected_trials_per_mode": 261, "trials_per_task_target": 3, "coverage_status": "partial", "audit_status": "reviewed_release_aligned", "recomputed_date": "2026-09-01"})
	}
	delete(rows[2].(map[string]any), "recomputed_date")
	raw, _ := json.Marshal(rows)
	return raw
}

type skillsbenchFetch struct {
	urls                   []string
	wrongRepo, change, bad bool
}

func (f *skillsbenchFetch) Fetch(_ context.Context, r fetcher.Request) (fetcher.Result, error) {
	f.urls = append(f.urls, r.URL)
	body := skillsbenchData()
	if strings.Contains(r.URL, "/api/datasets/") {
		body = []byte(fmt.Sprintf(`{"id":"benchflow/skillsbench-leaderboard","sha":%q,"gated":false,"lastModified":"2026-09-01T12:00:00Z"}`, strings.Repeat("a", 40)))
	} else if strings.Contains(r.URL, "api.github.com") {
		repo := strings.TrimPrefix(r.URL, "https://api.github.com/repos/")
		id := 42
		switch repo {
		case "anthropics/claude-code":
			id = 43
		case "google-gemini/gemini-cli":
			id = 44
		}
		if f.wrongRepo {
			repo = "fictional/other"
		}
		body = []byte(fmt.Sprintf(`{"id":%d,"full_name":%q}`, id, repo))
	} else {
		if f.change {
			body = append(body, ' ')
		}
		if f.bad {
			body = []byte(strings.Replace(string(body), "37.25", "-1", 1))
		}
	}
	return fetcher.Result{StatusCode: 200, FinalURL: r.URL, Body: body}, nil
}
func TestSkillsBenchSQLiteConfigurationScorePaging(t *testing.T) {
	c := skillsbenchClaim()
	now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
	path := filepath.Join(t.TempDir(), "skillsbench.db")
	store, e := outbox.Open(path, "fictional-node", now)
	if e != nil {
		t.Fatal(e)
	}
	defer func() { store.Close() }()
	if e = store.PersistClaim(c); e != nil {
		t.Fatal(e)
	}
	f := &skillsbenchFetch{}
	run, _ := scheduler.New(f, store, SkillsBench(), now, 5, time.Minute)
	first, e := run.Run(context.Background(), c)
	if e != nil || first.Finished || first.Observations != 30 {
		t.Fatal(first, e)
	}
	store.Close()
	store, e = outbox.Open(path, "fictional-node", now)
	if e != nil {
		t.Fatal(e)
	}
	run, _ = scheduler.New(f, store, SkillsBench(), now, 10, time.Minute)
	last, e := run.Run(context.Background(), c)
	count, _ := store.ObservationCount(context.Background(), c.Run.RunID)
	if e != nil || !last.Finished || count != 34 || len(f.urls) != 6 || f.urls[4] != f.urls[5] || !strings.Contains(f.urls[5], "/raw/"+strings.Repeat("a", 40)+"/") {
		t.Fatal(last, count, f.urls, e)
	}
	run, _ = scheduler.New(f, store, SkillsBench(), now, 10, time.Minute)
	if _, e = run.Run(context.Background(), c); e != nil || len(f.urls) != 6 {
		t.Fatal("terminal network", e)
	}
}
func TestSkillsBenchDoesNotInventModelsOrIndependentSkills(t *testing.T) {
	c := skillsbenchClaim()
	a := SkillsBench()
	f := &skillsbenchFetch{}
	cursor := ""
	items := []scheduler.Item{}
	for i := 0; i < 5; i++ {
		req, e := a.Request(c, cursor)
		if e != nil {
			t.Fatal(e)
		}
		r, _ := f.Fetch(context.Background(), req)
		p, e := a.Parse(c, r)
		if e != nil {
			t.Fatal(e)
		}
		cursor = p.NextCursor
		items = p.Items
	}
	for i, item := range items[:4] {
		value, e := client.ParseStrict(item.Payload)
		if e != nil {
			t.Fatal(e)
		}
		p := value.(map[string]any)
		config := p["evaluation_config"].(map[string]any)
		subject := p["subject"].(map[string]any)
		if config["model"] != nil || subject["category"] != "agent" || p["evaluation_scope"] != "configuration" || p["evaluated_at"] != nil || len(config["skills"].([]any)) != 0 {
			t.Fatal("invented attribution", p)
		}
		if i == 2 && p["score"] != nil {
			t.Fatal("unknown score zero")
		}
		if _, e = a.Freeze(c, item, time.Now()); e != nil {
			t.Fatal(e)
		}
		subject["category"] = "skill"
		item.Payload, _ = json.Marshal(p)
		if _, e = a.Freeze(c, item, time.Now()); e == nil {
			t.Fatal("skill entity score accepted")
		}
	}
}
func TestSkillsBenchRejectsBadScoreRepoAndChangedFile(t *testing.T) {
	for _, failure := range []string{"repo", "score", "file"} {
		t.Run(failure, func(t *testing.T) {
			c := skillsbenchClaim()
			now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
			store, e := outbox.Open(filepath.Join(t.TempDir(), "bad.db"), "fictional-node", now)
			if e != nil {
				t.Fatal(e)
			}
			defer store.Close()
			if e = store.PersistClaim(c); e != nil {
				t.Fatal(e)
			}
			f := &skillsbenchFetch{wrongRepo: failure == "repo", bad: failure == "score"}
			run, _ := scheduler.New(f, store, SkillsBench(), now, 5, time.Minute)
			_, e = run.Run(context.Background(), c)
			expected := 0
			if failure == "file" {
				if e != nil {
					t.Fatal(e)
				}
				expected = 30
				f.change = true
				run, _ = scheduler.New(f, store, SkillsBench(), now, 10, time.Minute)
				_, e = run.Run(context.Background(), c)
			}
			if e == nil {
				t.Fatal("bad evidence accepted")
			}
			count, _ := store.ObservationCount(context.Background(), c.Run.RunID)
			if count != expected {
				t.Fatal("bad page saved", count)
			}
		})
	}
}
