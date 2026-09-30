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

func hleClaim() client.RunClaim {
	c := parserClaim()
	c.Run.SourceID = "fictional-hle-source"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "hf-hle-openevals-v1"
	c.Config.Config.EntryURLs = []string{"https://huggingface.co/api/datasets/OpenEvals/leaderboard-data"}
	c.Config.Config.AllowedHosts = []string{"huggingface.co"}
	return c
}
func hleDocument(n int) []byte {
	models := []any{}
	for i := 0; i < n; i++ {
		var score any = map[string]any{"score": json.Number("42.75"), "confidence": "official", "source": "Fictional report", "date": "2026-09-01"}
		if i == 1 {
			score = nil
		}
		models = append(models, map[string]any{"name": fmt.Sprintf("fictional/Model-%02d", i), "id": fmt.Sprintf("fictional-model-%02d", i), "benchmarks": map[string]any{"hle": score}})
	}
	b, _ := json.Marshal(map[string]any{"metadata": map[string]any{"lastUpdated": "2026-09-01T12:00:00Z"}, "benchmarks": map[string]any{"hle": map[string]any{"id": "hle", "metric": "Accuracy", "metricUnit": "%", "huggingfaceUrl": "https://huggingface.co/datasets/cais/hle"}}, "models": models})
	return b
}

type hleFetcher struct {
	requests []string
	body     []byte
	change   bool
}

func (f *hleFetcher) Fetch(_ context.Context, r fetcher.Request) (fetcher.Result, error) {
	f.requests = append(f.requests, r.URL)
	b := f.body
	if strings.Contains(r.URL, "/api/datasets/") {
		b = []byte(fmt.Sprintf(`{"id":"OpenEvals/leaderboard-data","sha":%q,"gated":false,"siblings":[{"rfilename":"leaderboard.json"}]}`, strings.Repeat("a", 40)))
	} else if f.change {
		b = append(append([]byte{}, b...), ' ')
	}
	return fetcher.Result{StatusCode: 200, FinalURL: r.URL, Body: b}, nil
}
func TestHLEPinnedPaginationSQLiteAndUnknownScores(t *testing.T) {
	c := hleClaim()
	now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
	path := filepath.Join(t.TempDir(), "hle.db")
	store, e := outbox.Open(path, "fictional-node", now)
	if e != nil {
		t.Fatal(e)
	}
	defer func() { store.Close() }()
	if e = store.PersistClaim(c); e != nil {
		t.Fatal(e)
	}
	f := &hleFetcher{body: hleDocument(32)}
	run, _ := scheduler.New(f, store, HLE(), now, 2, time.Minute)
	first, e := run.Run(context.Background(), c)
	if e != nil || first.Finished || first.Observations != 30 {
		t.Fatal(first, e)
	}
	store.Close()
	store, e = outbox.Open(path, "fictional-node", now)
	if e != nil {
		t.Fatal(e)
	}
	run, _ = scheduler.New(f, store, HLE(), now, 10, time.Minute)
	last, e := run.Run(context.Background(), c)
	count, _ := store.ObservationCount(context.Background(), c.Run.RunID)
	if e != nil || !last.Finished || count != 32 || len(f.requests) != 3 || !strings.Contains(f.requests[2], "/raw/"+strings.Repeat("a", 40)+"/leaderboard.json") {
		t.Fatal(last, count, f.requests, e)
	}
	run, _ = scheduler.New(f, store, HLE(), now, 10, time.Minute)
	if _, e = run.Run(context.Background(), c); e != nil || len(f.requests) != 3 {
		t.Fatal("terminal refetch", e)
	}
	a := HLE()
	req, _ := a.Request(c, "")
	p, e := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(fmt.Sprintf(`{"id":"OpenEvals/leaderboard-data","sha":%q,"gated":false,"siblings":[{"rfilename":"leaderboard.json"}]}`, strings.Repeat("a", 40)))})
	if e != nil {
		t.Fatal(e)
	}
	req, _ = a.Request(c, p.NextCursor)
	p, e = a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: hleDocument(2)})
	if e != nil {
		t.Fatal(e)
	}
	for i, item := range p.Items {
		parsed, e := client.ParseStrict(item.Payload)
		if e != nil {
			t.Fatal(e)
		}
		v := parsed.(map[string]any)
		if v["evaluated_at"] != nil || v["provenance"] != "third_party" || v["benchmark_version"] != "aggregation_snapshot:"+strings.Repeat("a", 40) {
			t.Fatal("invented assessment", v)
		}
		if i == 1 && v["score"] != nil {
			t.Fatal("missing score became zero")
		}
		if _, e = a.Freeze(c, item, now()); e != nil {
			t.Fatal(e)
		}
		item.SourceItemID = "tampered"
		if _, e = a.Freeze(c, item, now()); e == nil {
			t.Fatal("identity tamper accepted")
		}
	}
}
func TestHLERejectsChangedPinnedFileAndInvalidScale(t *testing.T) {
	for _, changed := range []bool{false, true} {
		t.Run(fmt.Sprint(changed), func(t *testing.T) {
			c := hleClaim()
			now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
			store, e := outbox.Open(filepath.Join(t.TempDir(), "bad.db"), "fictional-node", now)
			if e != nil {
				t.Fatal(e)
			}
			defer store.Close()
			if e = store.PersistClaim(c); e != nil {
				t.Fatal(e)
			}
			f := &hleFetcher{body: hleDocument(32)}
			if !changed {
				f.body = []byte(strings.Replace(string(f.body), "42.75", "101", 1))
			}
			run, _ := scheduler.New(f, store, HLE(), now, 2, time.Minute)
			_, e = run.Run(context.Background(), c)
			if changed {
				if e != nil {
					t.Fatal(e)
				}
				f.change = true
				run, _ = scheduler.New(f, store, HLE(), now, 10, time.Minute)
				_, e = run.Run(context.Background(), c)
			}
			if e == nil {
				t.Fatal("invalid evidence accepted")
			}
			count, _ := store.ObservationCount(context.Background(), c.Run.RunID)
			expected := 0
			if changed {
				expected = 30
			}
			if count != expected {
				t.Fatal("partial invalid page", count)
			}
		})
	}
}
