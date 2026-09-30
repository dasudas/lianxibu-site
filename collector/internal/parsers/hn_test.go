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

func hnClaim() client.RunClaim {
	c := parserClaim()
	c.Run.SourceID = "global-hn"
	c.Config.SourceID = "global-hn"
	c.Config.Config.ParserVersion = "global-hn-v1"
	c.Config.Config.EntryURLs = []string{"https://hacker-news.firebaseio.com/v0/topstories.json"}
	c.Config.Config.AllowedHosts = []string{"hacker-news.firebaseio.com"}
	c.Config.Config.MaxItems = 30
	return c
}
func hnStart(t *testing.T, a *HNAdapter, c client.RunClaim, list string) scheduler.Page {
	t.Helper()
	req, err := a.Request(c, "")
	if err != nil {
		t.Fatal(err)
	}
	page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(list)})
	if err != nil {
		t.Fatal(err)
	}
	return page
}
func hnDetail(t *testing.T, a *HNAdapter, c client.RunClaim, cursor, body string) scheduler.Page {
	t.Helper()
	req, err := a.Request(c, cursor)
	if err != nil {
		t.Fatal(err)
	}
	page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(body)})
	if err != nil {
		t.Fatal(err)
	}
	return page
}
func TestHNSubmissionContentAndHeatIndependence(t *testing.T) {
	c := hnClaim()
	a := HackerNews()
	cursor := hnStart(t, a, c, "[901]").NextCursor
	when := time.Date(2026, 9, 30, 0, 0, 0, 0, time.UTC)
	body := fmt.Sprintf(`{"id":901,"type":"story","time":%d,"title":"Fictional &amp; <b>HN</b>","url":"https://example.invalid/story","text":"<p>HN summary</p><script>unsafe()</script>","score":1,"descendants":2}`, when.Unix())
	page := hnDetail(t, a, c, cursor, body)
	if len(page.Items) != 1 || !page.End {
		t.Fatal("story missing")
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.Title != "Fictional & HN" || article.PublishedAt != nil || article.UpdatedAt != nil || article.SubmittedAt == nil || article.TimeBasis != "submitted" || article.BodyText != nil || article.ContentStatus != "unavailable" || *article.SourceSummary != "HN summary" {
		t.Fatalf("time/content semantics changed %+v", article)
	}
	record, err := a.Freeze(c, page.Items[0], when.Add(time.Hour))
	if err != nil || record.ProtocolRevision != "0.12" {
		t.Fatal("submission freeze", err)
	}
	if _, err = client.DecodeObservation(record.Payload); err != nil {
		t.Fatal(err)
	}
	another := HackerNews()
	other := hnStart(t, another, c, "[901]")
	heated := hnDetail(t, another, c, other.NextCursor, strings.Replace(strings.Replace(body, `"score":1`, `"score":900`, 1), `"descendants":2`, `"descendants":999`, 1))
	if heated.Items[0].Version != page.Items[0].Version {
		t.Fatal("heat manufactured material version")
	}
	done, gaps, err := HackerNews().Completed(c, page.NextCursor)
	if err != nil || !done || len(gaps) == 0 {
		t.Fatal("completion did not survive process loss", err)
	}
}

type hnFixtureFetch struct{ requests []string }

func (f *hnFixtureFetch) Fetch(_ context.Context, r fetcher.Request) (fetcher.Result, error) {
	f.requests = append(f.requests, r.URL)
	body := "[901,901,902]"
	if strings.Contains(r.URL, "/item/901.json") {
		body = fmt.Sprintf(`{"id":901,"type":"story","time":%d,"title":"Fictional","text":"<p>Own story</p>"}`, time.Date(2026, 9, 30, 0, 0, 0, 0, time.UTC).Unix())
	}
	if strings.Contains(r.URL, "/item/902.json") {
		body = fmt.Sprintf(`{"id":902,"type":"story","time":%d,"title":"Outside"}`, time.Date(2026, 9, 30, 16, 0, 0, 0, time.UTC).Unix())
	}
	return fetcher.Result{StatusCode: 200, FinalURL: r.URL, Body: []byte(body)}, nil
}
func TestHNFixedListSQLiteRestartWindowAndTerminalResume(t *testing.T) {
	c := hnClaim()
	now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
	path := filepath.Join(t.TempDir(), "hn.db")
	store, err := outbox.Open(path, "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	if err = store.PersistClaim(c); err != nil {
		t.Fatal(err)
	}
	fetch := &hnFixtureFetch{}
	run, _ := scheduler.New(fetch, store, HackerNews(), now, 1, time.Minute)
	first, err := run.Run(context.Background(), c)
	if err != nil || first.Finished || first.Cursor == "" || first.Observations != 0 {
		t.Fatal("list checkpoint", first, err)
	}
	store.Close()
	store, err = outbox.Open(path, "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	run, _ = scheduler.New(fetch, store, HackerNews(), now, 10, time.Minute)
	result, err := run.Run(context.Background(), c)
	if err != nil || !result.Finished || result.Observations != 1 || len(fetch.requests) != 3 {
		t.Fatal("snapshot/window restore", result, err)
	}
	state, _, _ := store.Run(context.Background(), c.Run.RunID)
	if state.Cursor == "" {
		t.Fatal("terminal position not persisted")
	}
	restarted, _ := scheduler.New(fetch, store, HackerNews(), now, 10, time.Minute)
	result, err = restarted.Run(context.Background(), c)
	if err != nil || !result.Finished || result.Observations != 0 || len(fetch.requests) != 3 {
		t.Fatal("terminal resume refetched heat list", result, err)
	}
}

func TestHNLimitMalformedSourceAndCursor(t *testing.T) {
	c := hnClaim()
	a := HackerNews()
	ids := make([]int, 40)
	for i := range ids {
		ids[i] = 1000 + i
	}
	raw, _ := json.Marshal(ids)
	page := hnStart(t, a, c, string(raw))
	var state map[string]any
	json.Unmarshal([]byte(page.NextCursor), &state)
	if len(state["ids"].([]any)) != 30 || len(page.Gaps) < 2 {
		t.Fatal("detail cap not explicit")
	}
	changed := c
	changed.Run.RunID = "different-run"
	if _, err := HackerNews().Request(changed, page.NextCursor); err == nil {
		t.Fatal("cross-run cursor accepted")
	}
	if _, err := a.Request(c, strings.Replace(page.NextCursor, `"index":0`, `"index":-1`, 1)); err == nil {
		t.Fatal("negative cursor accepted")
	}
	req, _ := a.Request(c, page.NextCursor)
	if _, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(`{"id":1000,"id":1001}`)}); err == nil {
		t.Fatal("duplicate JSON accepted")
	}
	for _, body := range []string{`null`, `{"id":1000,"deleted":true}`, `{"id":1000,"type":"comment"}`, `{"id":1000,"type":"story","title":"Missing time"}`} {
		page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(body)})
		if err != nil || len(page.Items) != 0 || page.NextCursor == "" {
			t.Fatal("filtered detail did not advance safely", err)
		}
	}
}

func TestHNTextOwnershipAndTruncation(t *testing.T) {
	c := hnClaim()
	for _, tc := range []struct {
		name, url, text, status string
		body                    bool
		truncated               bool
	}{
		{"own text", "", "<p>Own story</p>", "full", true, false},
		{"plain HTTP link", "http://example.invalid/story", "<p>Discussion</p>", "unavailable", false, false},
		{"bounded own text", "", strings.Repeat("😀", 50001), "partial", true, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := HackerNews()
			cursor := hnStart(t, a, c, "[901]").NextCursor
			body, _ := json.Marshal(map[string]any{"id": 901, "type": "story", "time": 1790726400, "title": "Fictional", "url": tc.url, "text": tc.text})
			page := hnDetail(t, a, c, cursor, string(body))
			if len(page.Items) != 1 {
				t.Fatal("missing story")
			}
			var article Article
			json.Unmarshal(page.Items[0].Payload, &article)
			if (article.BodyText != nil) != tc.body || article.ContentStatus != tc.status || article.BodyTruncated != tc.truncated {
				t.Fatal("content ownership/bounds changed")
			}
			if tc.url != "" && (article.SourceURL != "https://news.ycombinator.com/item?id=901" || !strings.Contains(*article.SourceSummary, tc.url)) {
				t.Fatal("plain HTTP converted or lost")
			}
			if _, err := a.Freeze(c, page.Items[0], time.Now()); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestHNThirtyDetailsThenNoRequest(t *testing.T) {
	c := hnClaim()
	a := HackerNews()
	ids := make([]int, 40)
	for i := range ids {
		ids[i] = 1000 + i
	}
	raw, _ := json.Marshal(ids)
	cursor := hnStart(t, a, c, string(raw)).NextCursor
	for i := 0; i < 30; i++ {
		page := hnDetail(t, a, c, cursor, fmt.Sprintf(`{"id":%d,"deleted":true}`, 1000+i))
		if page.End != (i == 29) {
			t.Fatal("detail limit changed")
		}
		cursor = page.NextCursor
	}
	if done, _, err := HackerNews().Completed(c, cursor); err != nil || !done {
		t.Fatal("terminal proof missing", err)
	}
	if _, err := a.Request(c, cursor); err == nil {
		t.Fatal("31st detail allowed")
	}
}
