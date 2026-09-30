package parsers

import (
	"context"
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func releaseClaim(source string) client.RunClaim {
	c := parserClaim()
	c.Run.SourceID = source
	c.Config.SourceID = source
	c.Config.Config.ParserVersion = source + "-v1"
	c.Config.Config.EntryURLs = []string{"https://api.github.com/repos/fictional/release-fixture/releases?per_page=30"}
	c.Config.Config.AllowedHosts = []string{"api.github.com"}
	return c
}
func fictionalRelease(id int, published, body string) string {
	raw, _ := json.Marshal(map[string]any{"id": id, "name": "Fictional release", "tag_name": "v1-fictional", "html_url": "https://github.com/fictional/release-fixture/releases/tag/v1-fictional", "draft": false, "prerelease": false, "published_at": published, "body": body})
	return string(raw)
}
func releaseStart(t *testing.T, a *ReleasesAdapter, c client.RunClaim) string {
	t.Helper()
	req, err := a.Request(c, "")
	if err != nil {
		t.Fatal(err)
	}
	page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(`{"id":99102,"full_name":"fictional/release-fixture"}`)})
	if err != nil {
		t.Fatal(err)
	}
	return page.NextCursor
}
func TestReleasesBoundedPaginationDuplicateJSONAndUnsafeCursor(t *testing.T) {
	c := releaseClaim("global-react")
	a := React()
	cursor := releaseStart(t, a, c)
	req, err := a.Request(c, cursor)
	if err != nil {
		t.Fatal(err)
	}
	one := fictionalRelease(77102, "2026-09-30T00:00:00Z", "body")
	full := make([]string, 30)
	for i := range full {
		full[i] = one
	}
	page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte("[" + strings.Join(full, ",") + "]")})
	if err != nil || page.End || len(page.Items) != 1 || page.NextCursor == cursor {
		t.Fatal("full page lost cursor or duplicates")
	}
	restarted := React()
	req, err = restarted.Request(c, page.NextCursor)
	if err != nil || !strings.Contains(req.URL, "page=2") {
		t.Fatal("page position not restored")
	}
	for _, bad := range []string{`[{"id":1,"id":2}]`, `[] {}`, `null`, strings.Repeat("[", 66) + strings.Repeat("]", 66)} {
		if _, err = restarted.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(bad)}); err == nil {
			t.Fatalf("unsafe JSON accepted %s", bad)
		}
	}
	badCursor := strings.Replace(page.NextCursor, `"repository_id":99102`, `"repository_id":-1`, 1)
	if _, err = restarted.Request(c, badCursor); err == nil {
		t.Fatal("invalid repository ID accepted")
	}
	page, err = restarted.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(`[]`)})
	if err != nil || !page.End {
		t.Fatal("empty page did not finish")
	}
}

type releaseFixtureFetch struct{ body string }

func (f releaseFixtureFetch) Fetch(_ context.Context, request fetcher.Request) (fetcher.Result, error) {
	body := `{"id":99102,"full_name":"fictional/release-fixture"}`
	if strings.Contains(request.URL, "/releases?") {
		body = f.body
	}
	return fetcher.Result{StatusCode: 200, FinalURL: request.URL, Body: []byte(body)}, nil
}
func TestReleasesSQLiteCursorRestartWindowAndDedup(t *testing.T) {
	c := releaseClaim("global-react")
	now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
	path := filepath.Join(t.TempDir(), "fictional.db")
	store, err := outbox.Open(path, "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	if err = store.PersistClaim(c); err != nil {
		t.Fatal(err)
	}
	one := fictionalRelease(77102, "2026-09-30T00:00:00Z", "body")
	outside := fictionalRelease(77103, "2026-10-01T00:00:00Z", "outside")
	fetch := releaseFixtureFetch{body: "[" + one + "," + one + "," + outside + "]"}
	first, err := scheduler.New(fetch, store, React(), now, 1, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	out, err := first.Run(context.Background(), c)
	if err != nil || out.Finished || out.Cursor == "" || out.Observations != 0 {
		t.Fatalf("metadata checkpoint %v %+v", err, out)
	}
	store.Close()
	store, err = outbox.Open(path, "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	second, err := scheduler.New(fetch, store, React(), now, 3, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	out, err = second.Run(context.Background(), c)
	if err != nil || !out.Finished || out.Observations != 1 {
		t.Fatalf("restart %v %+v", err, out)
	}
	out, err = second.Run(context.Background(), c)
	if err != nil || out.Observations != 0 {
		t.Fatalf("duplicate resume %v %+v", err, out)
	}
}
