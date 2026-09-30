package parsers

import (
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"strings"
	"testing"
	"time"
)

func TestReactRepositoryIDReleaseIDAndRestartCursor(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = "global-react"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "global-react-v1"
	c.Config.Config.EntryURLs = []string{"https://api.github.com/repos/fictional/react-fixture/releases?per_page=30"}
	c.Config.Config.AllowedHosts = []string{"api.github.com"}
	a := React()
	req, err := a.Request(c, "")
	if err != nil || req.URL != "https://api.github.com/repos/fictional/react-fixture" || req.Format != fetcher.JSON {
		t.Fatalf("metadata request %v %+v", err, req)
	}
	meta, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(`{"id":99101,"full_name":"fictional/react-fixture"}`)})
	if err != nil || meta.End || meta.NextCursor == "" || len(meta.Items) != 0 {
		t.Fatalf("metadata %v %+v", err, meta)
	}
	a = React()
	req, err = a.Request(c, meta.NextCursor)
	if err != nil || !strings.Contains(req.URL, "per_page=30") || !strings.Contains(req.URL, "page=1") {
		t.Fatal("cursor restart failed")
	}
	raw := `{"id":77101,"name":"Fictional release","tag_name":"v1.0-fictional","html_url":"https://github.com/fictional/react-fixture/releases/tag/v1.0-fictional","draft":false,"prerelease":false,"published_at":"2026-09-30T08:00:00+08:00","created_at":"2020-01-01T00:00:00Z","body":"# Release\n\n**Safe** body\n\n<script>hidden()</script>\n\n![image](https://example.invalid/attachment)"}`
	page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte("[" + raw + "]")})
	if err != nil || len(page.Items) != 1 || page.Items[0].SourceItemID != "99101:77101" || !page.End {
		t.Fatalf("release %v %+v", err, page)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.PublishedAt == nil || *article.PublishedAt != "2026-09-30T00:00:00.000000Z" || article.UpdatedAt != nil || article.BodyText == nil || strings.Contains(*article.BodyText, "hidden") || !strings.Contains(*article.BodyText, "Safe body") {
		t.Fatalf("source time/body %+v", article)
	}
	if _, err = a.Freeze(c, page.Items[0], time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(strings.Join(page.Gaps, ","), "release_revision_time_unknown") {
		t.Fatal("unknown update coverage hidden")
	}
	_, err = a.Request(c, meta.NextCursor)
	if err != nil {
		t.Fatal(err)
	}
	bad := strings.Replace(raw, `"draft":false`, `"draft":true`, 1)
	preview := strings.Replace(raw, `"prerelease":false`, `"prerelease":true`, 1)
	missing := strings.Replace(raw, `"published_at":"2026-09-30T08:00:00+08:00"`, `"published_at":null`, 1)
	page, err = a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte("[" + bad + "," + preview + "," + missing + "]")})
	if err != nil || len(page.Items) != 0 || !strings.Contains(strings.Join(page.Gaps, ","), "entry_time_unknown") {
		t.Fatal("draft/prerelease/missing time boundary failed")
	}
	other := c
	other.Run.RunID = "other-fictional-run"
	if _, err = a.Request(other, meta.NextCursor); err == nil {
		t.Fatal("cross-run cursor accepted")
	}
}
