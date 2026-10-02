package parsers

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
)

func fictionalLegacyArticle(href string) fetcher.Result {
	raw := entry("fictional-stable-entry", "2026-09-30T08:00:00+08:00", "2026-09-30T09:00:00+08:00", "fictional body")
	return atom(strings.Replace(raw, "https://example.invalid/post/1", href, 1))
}

func TestRuanyifengVerifiedHistoricalArticleLinkBecomesHTTPS(t *testing.T) {
	c := parserClaim()
	for _, href := range []string{"http://www.ruanyifeng.com/blog/fictional/post/1", "http://www.ruanyifeng.com:80/blog/fictional/post/1#fictional"} {
		page, e := Ruanyifeng().Parse(c, fictionalLegacyArticle(href))
		if e != nil || len(page.Items) != 1 {
			t.Fatalf("historical article rejected: %v, %v", e, page.Gaps)
		}
		item := page.Items[0]
		if item.SourceItemID != "fictional-stable-entry" {
			t.Fatal("source ID rewritten")
		}
		var a Article
		if e = json.Unmarshal(item.Payload, &a); e != nil || a.SourceURL != "https://www.ruanyifeng.com/blog/fictional/post/1" {
			t.Fatal("wrong HTTPS counterpart", e)
		}
		frozen, e := Ruanyifeng().Freeze(c, item, time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC))
		if e != nil {
			t.Fatal(e)
		}
		if _, e = client.DecodeObservation(frozen.Payload); e != nil {
			t.Fatal("normalized observation invalid", e)
		}
	}
}

func TestRuanyifengLinkUpgradeCannotRelaxFetchOrOtherSources(t *testing.T) {
	c := parserClaim()
	for _, href := range []string{"http://example.invalid/blog/post/1", "http://www.ruanyifeng.com.evil.invalid/blog/post/1", "http://user@www.ruanyifeng.com/blog/post/1", "http://www.ruanyifeng.com:8080/blog/post/1", "http://www.ruanyifeng.com/fictional/post/1", "http://www.ruanyifeng.com/blog/post/1?fixture=1", "http://www.ruanyifeng.com/blog/../secret", "http://www.ruanyifeng.com/blog/%2e%2e/secret", "http://127.0.0.1/blog/post/1"} {
		page, e := Ruanyifeng().Parse(c, fictionalLegacyArticle(href))
		if e != nil || len(page.Items) != 0 || !strings.Contains(strings.Join(page.Gaps, ","), "entry_link_invalid") {
			t.Fatal("unsafe HTTP metadata accepted", href, e)
		}
	}
	c.Config.Config.EntryURLs = []string{"http://www.ruanyifeng.com/blog/atom.xml"}
	c.Config.Config.AllowedHosts = []string{"www.ruanyifeng.com"}
	if _, e := Ruanyifeng().Request(c, ""); e == nil {
		t.Fatal("HTTP fetch entry upgraded")
	}
	c.Config.Config.EntryURLs = []string{"https://www.ruanyifeng.com/blog/atom.xml"}
	if _, e := Ruanyifeng().Request(c, "http://www.ruanyifeng.com/blog/page2"); e == nil {
		t.Fatal("HTTP cursor upgraded")
	}
	c = parserClaim()
	c.Run.SourceID = "cn-cnblogs"
	c.Config.SourceID = "cn-cnblogs"
	c.Config.Config.ParserVersion = "cn-cnblogs-v1"
	page, e := Cnblogs().Parse(c, fictionalLegacyArticle("http://www.ruanyifeng.com/blog/fictional/post/1"))
	if e != nil || len(page.Items) != 0 {
		t.Fatal("other adapter accepted HTTP", e)
	}
}

// Actual source bytes stay outside Git; this opt-in checks this adapter only,
// and cannot establish daily window coverage, rights or seven-day stability.
func TestRuanyifengAdmissionPrivateAtomSample(t *testing.T) {
	path := os.Getenv("A01_PRIVATE_ATOM_SAMPLE")
	if path == "" {
		t.Skip("private Japan-node sample is opt-in")
	}
	raw, e := os.ReadFile(path)
	if e != nil {
		t.Fatal("private sample unavailable")
	}
	c := parserClaim()
	c.Config.Config.EntryURLs = []string{"https://www.ruanyifeng.com/blog/atom.xml"}
	c.Config.Config.AllowedHosts = []string{"www.ruanyifeng.com"}
	page, e := Ruanyifeng().Parse(c, fetcher.Result{StatusCode: 200, ContentType: "application/xml", FinalURL: c.Config.Config.EntryURLs[0], Body: raw})
	if e != nil || len(page.Items) < 3 {
		t.Fatalf("no three valid source items: error=%v, gaps=%v", e, page.Gaps)
	}
	for _, item := range page.Items {
		r, e := Ruanyifeng().Freeze(c, item, time.Date(2026, 10, 2, 4, 0, 0, 0, time.UTC))
		if e != nil {
			t.Fatal("sample normalization failed")
		}
		wire, e := client.DecodeObservation(r.Payload)
		if e != nil || wire.SourceItemID != item.SourceItemID {
			t.Fatal("sample wire identity invalid")
		}
	}
	t.Logf("parsed_items=%d; coverage_unproven=%v; no source text emitted", len(page.Items), strings.Contains(strings.Join(page.Gaps, ","), "feed_coverage_unproven"))
}
