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

func livebenchClaim() client.RunClaim {
	c := parserClaim()
	c.Run.SourceID = "fictional-livebench"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "livebench-v1"
	c.Config.Config.EntryURLs = []string{"https://api.github.com/repos/LiveBench/new-livebench/commits/main", "https://raw.githubusercontent.com/LiveBench/new-livebench/main/public/table_2026_06_25.csv"}
	c.Config.Config.AllowedHosts = []string{"api.github.com", "raw.githubusercontent.com"}
	return c
}

type livebenchFetch struct {
	urls        []string
	change, bad bool
}

func (f *livebenchFetch) Fetch(_ context.Context, r fetcher.Request) (fetcher.Result, error) {
	f.urls = append(f.urls, r.URL)
	body := ""
	switch {
	case strings.Contains(r.URL, "api.github.com"):
		body = fmt.Sprintf(`{"sha":%q,"commit":{"committer":{"date":"2026-09-01T12:00:00Z"}}}`, strings.Repeat("a", 40))
	case strings.Contains(r.URL, "categories_"):
		body = `{"Fictional category":["reasoning","coding"]}`
	case strings.Contains(r.URL, "modelLinks.js"):
		body = `export const modelLinks = {"fictional-base": {url: "https://example.invalid/model", huggingface: "https://huggingface.co/fictional/Model", variants: [{rawName:"fictional-variant"}]},"unmapped": {url:"https://example.invalid/unknown"}}; throw new Error("Do not execute");`
	default:
		body = "model,reasoning,coding\nfictional-base,42.75,\n"
		for i := 0; i < 26; i++ {
			body += fmt.Sprintf("fictional-base-%d,10,20\n", i)
		}
		if f.bad {
			body = strings.Replace(body, "42.75", "101", 1)
		}
		if f.change {
			body += "unmapped,3,4\n"
		}
	}
	// Extra rows each receive an explicit registry link; no name guessing.
	if strings.Contains(r.URL, "modelLinks.js") {
		body = `export const modelLinks = {"fictional-base": {url:"https://huggingface.co/fictional/Model"},`
		for i := 0; i < 26; i++ {
			body += fmt.Sprintf(`"fictional-base-%d": {huggingface:"https://huggingface.co/fictional/Model-%d"},`, i, i)
		}
		body += `"unmapped":{url:"https://example.invalid/model"}}; process.exit(1);`
	}
	return fetcher.Result{StatusCode: 200, FinalURL: r.URL, Body: []byte(body)}, nil
}
func TestLiveBenchFixedReleaseSQLiteMetricPaging(t *testing.T) {
	c := livebenchClaim()
	now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
	path := filepath.Join(t.TempDir(), "livebench.db")
	store, e := outbox.Open(path, "fictional-node", now)
	if e != nil {
		t.Fatal(e)
	}
	defer func() { store.Close() }()
	if e = store.PersistClaim(c); e != nil {
		t.Fatal(e)
	}
	f := &livebenchFetch{}
	run, _ := scheduler.New(f, store, LiveBench(), now, 4, time.Minute)
	first, e := run.Run(context.Background(), c)
	if e != nil || first.Finished || first.Observations != 50 {
		t.Fatal(first, e)
	}
	store.Close()
	store, e = outbox.Open(path, "fictional-node", now)
	if e != nil {
		t.Fatal(e)
	}
	run, _ = scheduler.New(f, store, LiveBench(), now, 10, time.Minute)
	last, e := run.Run(context.Background(), c)
	count, _ := store.ObservationCount(context.Background(), c.Run.RunID)
	if e != nil || !last.Finished || count != 54 || len(f.urls) != 5 {
		t.Fatal(last, count, f.urls, e)
	}
	for _, u := range f.urls[1:] {
		if !strings.Contains(u, "/"+strings.Repeat("a", 40)+"/") {
			t.Fatal("moving ref", u)
		}
	}
	run, _ = scheduler.New(f, store, LiveBench(), now, 10, time.Minute)
	if _, e = run.Run(context.Background(), c); e != nil || len(f.urls) != 5 {
		t.Fatal("terminal network", e)
	}
}
func TestLiveBenchBadScoreAndSnapshotChangeDoNotSavePage(t *testing.T) {
	for _, change := range []bool{false, true} {
		t.Run(fmt.Sprint(change), func(t *testing.T) {
			c := livebenchClaim()
			now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
			store, e := outbox.Open(filepath.Join(t.TempDir(), "bad.db"), "fictional-node", now)
			if e != nil {
				t.Fatal(e)
			}
			defer store.Close()
			if e = store.PersistClaim(c); e != nil {
				t.Fatal(e)
			}
			f := &livebenchFetch{bad: !change}
			run, _ := scheduler.New(f, store, LiveBench(), now, 4, time.Minute)
			_, e = run.Run(context.Background(), c)
			expected := 0
			if change {
				if e != nil {
					t.Fatal(e)
				}
				f.change = true
				run, _ = scheduler.New(f, store, LiveBench(), now, 10, time.Minute)
				_, e = run.Run(context.Background(), c)
				expected = 50
			}
			if e == nil {
				t.Fatal("bad evidence accepted")
			}
			count, _ := store.ObservationCount(context.Background(), c.Run.RunID)
			if count != expected {
				t.Fatal("invalid page saved", count)
			}
		})
	}
}
func TestLiveBenchStaticMappingDoesNotExecuteOrGuess(t *testing.T) {
	text := `// never eval
export const modelLinks = {
 "base": {url:"https://example.invalid/model",huggingface:"https://huggingface.co/fictional/Model",variants:[{rawName:"variant",huggingface:"https://huggingface.co/other/Model"}]},
 "other": {url:"https://huggingface.co/fictional/Other"},
 "bad":{huggingface:"https://huggingface.co/models?search=guess"}
}; destructive();`
	m, e := livebenchMappings([]byte(text))
	if e != nil || len(m) != 2 || m["base"] != "fictional/Model" || m["other"] != "fictional/Other" || m["variant"] != "" {
		t.Fatal(m, e)
	}
	if _, e = livebenchMappings([]byte(`export const modelLinks={"a":{},"a":{}};`)); e == nil {
		t.Fatal("duplicate mapping")
	}
}
func TestLiveBenchPayloadPreservesUnknownEvaluation(t *testing.T) {
	c := livebenchClaim()
	a := LiveBench()
	f := &livebenchFetch{}
	cursor := ""
	var items []scheduler.Item
	for i := 0; i < 4; i++ {
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
	for i, item := range items[:2] {
		v, e := client.ParseStrict(item.Payload)
		if e != nil {
			t.Fatal(e)
		}
		p := v.(map[string]any)
		if p["evaluation_scope"] != "configuration" || p["evaluated_at"] != nil || p["benchmark_version"] != "2026-06-25" || p["provenance"] != "third_party" {
			t.Fatal(p)
		}
		if i == 1 && p["score"] != nil {
			t.Fatal("unknown became zero")
		}
		if _, e = a.Freeze(c, item, time.Now()); e != nil {
			t.Fatal(e)
		}
		p["benchmark_version"] = "2026-06-26"
		item.Payload, _ = json.Marshal(p)
		if _, e = a.Freeze(c, item, time.Now()); e == nil {
			t.Fatal("foreign release")
		}
	}
}
