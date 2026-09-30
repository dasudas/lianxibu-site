package parsers

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const arxivFixtureEntry = "https://export.arxiv.org/api/query?search_query=cat%3Acs.AI&start=0&max_results=20&sortBy=submittedDate&sortOrder=descending"

func arxivClaim() client.RunClaim {
	c := parserClaim()
	c.Run.SourceID = "global-arxiv"
	c.Config.SourceID = "global-arxiv"
	c.Config.Config.ParserVersion = "global-arxiv-v1"
	c.Config.Config.EntryURLs = []string{arxivFixtureEntry}
	c.Config.Config.AllowedHosts = []string{"export.arxiv.org"}
	c.Config.Config.MaxItems = 20
	return c
}
func arxivEntry(id, updated string) string {
	return fmt.Sprintf(`<entry><id>http://arxiv.org/abs/%s</id><title>Fictional paper</title><published>2026-09-01T12:00:00Z</published><updated>%s</updated><summary> Abstract only </summary><author><name>Fictional Author</name></author><category term="cs.AI"/><link rel="alternate" href="http://arxiv.org/abs/%s"/><link rel="related" type="application/pdf" title="pdf" href="http://arxiv.org/pdf/%s"/></entry>`, id, updated, id, id)
}
func arxivFeed(entries string) []byte {
	return []byte(`<feed xmlns="http://www.w3.org/2005/Atom" xmlns:o="http://a9.com/-/spec/opensearch/1.1/"><o:totalResults>200</o:totalResults><o:startIndex>0</o:startIndex><o:itemsPerPage>20</o:itemsPerPage>` + entries + `</feed>`)
}
func TestArxivStablePaperVersionAndAbstract(t *testing.T) {
	c := arxivClaim()
	a := Arxiv()
	req, err := a.Request(c, "")
	if err != nil || req.MinInterval != 3*time.Second || req.URL != arxivFixtureEntry {
		t.Fatal("request boundary", err)
	}
	page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: arxivFeed(arxivEntry("2609.00001v1", "2026-09-01T12:00:00Z") + arxivEntry("2609.00001v2", "2026-09-30T12:00:00Z"))})
	if err != nil || !page.End || len(page.Items) != 1 || page.Items[0].SourceItemID != "2609.00001" || page.Items[0].Version != "2026-09-30T12:00:00.000000Z" {
		t.Fatal("stable version", page, err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.SourceURL != "https://arxiv.org/abs/2609.00001v2" || article.BodyText != nil || article.ContentStatus != "unavailable" || *article.SourceSummary != "Abstract only" || article.PublishedAt == nil || article.UpdatedAt == nil || article.TimeBasis != "updated" || len(article.Authors) != 1 {
		t.Fatal("abstract or time meaning changed", article)
	}
	row, err := a.Freeze(c, page.Items[0], time.Now())
	if err != nil || row.ProtocolRevision != "0.11" {
		t.Fatal("wire", err)
	}
	if done, gaps, err := Arxiv().Completed(c, page.NextCursor); err != nil || !done || len(gaps) < 2 {
		t.Fatal("terminal coverage proof", err)
	}
	if _, err := Arxiv().Request(c, page.NextCursor); err == nil {
		t.Fatal("terminal refetch")
	}
	changed := c
	changed.Run.RunID = "other"
	if _, _, err := Arxiv().Completed(changed, page.NextCursor); err == nil {
		t.Fatal("cross-run cursor")
	}
}

type arxivFixtureFetch struct{ requests []string }

func (f *arxivFixtureFetch) Fetch(_ context.Context, r fetcher.Request) (fetcher.Result, error) {
	f.requests = append(f.requests, r.URL)
	u, _ := url.Parse(r.URL)
	offset := u.Query().Get("start")
	entries := ""
	if offset == "0" {
		for i := 0; i < 20; i++ {
			entries += arxivEntry(fmt.Sprintf("2609.%05dv1", i+1), "2026-09-30T12:00:00Z")
		}
	}
	if offset == "20" {
		entries = arxivEntry("2609.00021v1", "2026-09-30T16:00:00Z")
	}
	body := arxivFeed(entries)
	body = []byte(strings.Replace(strings.Replace(string(body), "<o:startIndex>0", "<o:startIndex>"+offset, 1), "<o:totalResults>200", "<o:totalResults>21", 1))
	return fetcher.Result{StatusCode: 200, FinalURL: r.URL, Body: body}, nil
}
func TestArxivSQLitePagingWindowAndCompletion(t *testing.T) {
	c := arxivClaim()
	now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
	path := filepath.Join(t.TempDir(), "arxiv.db")
	store, err := outbox.Open(path, "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	if err = store.PersistClaim(c); err != nil {
		t.Fatal(err)
	}
	fetch := &arxivFixtureFetch{}
	run, _ := scheduler.New(fetch, store, Arxiv(), now, 1, time.Minute)
	first, err := run.Run(context.Background(), c)
	if err != nil || first.Finished || first.Observations != 20 {
		t.Fatal("first page", first, err)
	}
	store.Close()
	store, err = outbox.Open(path, "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	run, _ = scheduler.New(fetch, store, Arxiv(), now, 10, time.Minute)
	second, err := run.Run(context.Background(), c)
	if err != nil || !second.Finished || second.Observations != 0 || len(fetch.requests) != 2 {
		t.Fatal("restart/window", second, err)
	}
	run, _ = scheduler.New(fetch, store, Arxiv(), now, 10, time.Minute)
	terminal, err := run.Run(context.Background(), c)
	if err != nil || !terminal.Finished || len(fetch.requests) != 2 {
		t.Fatal("completed run refetch", err)
	}
}
func TestArxivMalformedEntriesAndResponseLimits(t *testing.T) {
	c := arxivClaim()
	for _, entry := range []string{arxivEntry("not-a-paper", "2026-09-30T12:00:00Z"), strings.Replace(arxivEntry("2609.00001v2", "2026-09-30T12:00:00Z"), "http://arxiv.org/abs/2609.00001v2", "https://example.invalid/abs/2609.00001v2", 1), arxivEntry("2609.00001v2", "unknown"), strings.Replace(arxivEntry("2609.00001v2", "2026-09-30T12:00:00Z"), `href="http://arxiv.org/abs/2609.00001v2"`, `href="http://arxiv.org/abs/2609.00002v2"`, 1)} {
		a := Arxiv()
		req, _ := a.Request(c, "")
		page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: arxivFeed(entry)})
		if err != nil || len(page.Items) != 0 || len(page.Gaps) < 2 {
			t.Fatal("bad entry not isolated", err)
		}
	}
	for _, body := range [][]byte{arxivFeed(strings.Repeat(arxivEntry("2609.00001v1", "2026-09-30T12:00:00Z"), 21)), []byte(`<!DOCTYPE feed><feed xmlns="http://www.w3.org/2005/Atom"/>`), []byte(`<feed/>`)} {
		a := Arxiv()
		req, _ := a.Request(c, "")
		if _, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: body}); err == nil {
			t.Fatal("unsafe/oversized document")
		}
	}
	changed := c
	changed.Config.Config.EntryURLs = []string{strings.Replace(arxivFixtureEntry, "max_results=20", "max_results=200", 1)}
	if _, err := Arxiv().Request(changed, ""); err == nil {
		t.Fatal("unbounded query")
	}
}
