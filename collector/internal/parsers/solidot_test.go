package parsers

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
)

func rssClaim() (cSource string) { return "cn-solidot" }
func rss(items string) fetcher.Result {
	return fetcher.Result{StatusCode: 200, FinalURL: "https://example.invalid/feed", Body: []byte(`<rss version="2.0"><channel><title>Fictional feed</title>` + items + `</channel></rss>`)}
}
func TestSolidotURLIdentityAndDescriptionIsOnlySummary(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = rssClaim()
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "cn-solidot-v1"
	a := Solidot()
	page, err := a.Parse(c, rss(`<item><title>Fictional title</title><link>https://EXAMPLE.invalid:443/post?id=7&amp;b=2#fragment</link><guid>unstable-guid</guid><pubDate>Wed, 30 Sep 2026 08:00:00 +0800</pubDate><description><![CDATA[<p>Summary <b>only</b></p><script>bad()</script>]]></description></item>`))
	if err != nil || len(page.Items) != 1 {
		t.Fatal(err)
	}
	item := page.Items[0]
	if item.SourceItemID != "https://example.invalid/post?b=2&id=7" || item.EvidenceTime.Format(time.RFC3339) != "2026-09-30T00:00:00Z" {
		t.Fatalf("URL/date: %+v", item)
	}
	var article Article
	json.Unmarshal(item.Payload, &article)
	if article.BodyText != nil || article.ContentStatus != "unavailable" || article.SourceSummary == nil || *article.SourceSummary != "Summary only" || article.RawPublished != "Wed, 30 Sep 2026 08:00:00 +0800" {
		t.Fatalf("summary promoted to body: %+v", article)
	}
	record, err := a.Freeze(c, item, time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC))
	if err != nil || !strings.Contains(string(record.Payload), `"body_text":null`) {
		t.Fatal("invalid frozen summary", err)
	}
	request, err := a.Request(c, "")
	if err != nil || request.Format != fetcher.RSS {
		t.Fatal("RSS request", err)
	}
}
func TestSolidotMalformedOrMissingSourceEvidenceLeavesGap(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = "cn-solidot"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "cn-solidot-v1"
	a := Solidot()
	bad := `<item><title>Missing time</title><link>https://example.invalid/one</link></item><item><title>Bad scheme</title><link>javascript:bad()</link><pubDate>Wed, 30 Sep 2026 08:00:00 +0800</pubDate></item>`
	page, err := a.Parse(c, rss(bad))
	if err != nil || len(page.Items) != 0 || len(page.Gaps) < 3 {
		t.Fatalf("bad rows not isolated: %v %+v", err, page)
	}
	empty := rss("")
	empty.Body = []byte(`<rss><channel/><channel/></rss>`)
	if _, err := a.Parse(c, empty); err == nil {
		t.Fatal("ambiguous channels accepted")
	}
	page, err = a.Parse(c, atom(""))
	if err == nil {
		t.Fatal("Atom accepted as RSS")
	}
}
func TestSolidotCanonicalLinkDedupAndSummaryBound(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = "cn-solidot"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "cn-solidot-v1"
	a := Solidot()
	template := `<item><title>Title</title><link>https://example.invalid/post?a=1&amp;b=2%s</link><pubDate>Wed, 30 Sep 2026 08:00:00 +0800</pubDate><description>summary</description></item>`
	first := strings.ReplaceAll(template, "%s", "#one")
	second := strings.ReplaceAll(template, "%s", "#two")
	page, err := a.Parse(c, rss(first+second))
	if err != nil || len(page.Items) != 1 {
		t.Fatalf("canonical duplicate: %v", err)
	}
	page, err = a.Parse(c, rss(strings.Replace(first, "summary", strings.Repeat("x", 17000), 1)))
	if err != nil || len(page.Items) != 1 {
		t.Fatal(err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if !article.BodyTruncated || len(*article.SourceSummary) > 16384 {
		t.Fatal("summary bound ignored")
	}
	if link, err := canonicalLink("https://EXAMPLE.invalid:8443/post#part", ""); err != nil || link != "https://example.invalid:8443/post" {
		t.Fatal("nondefault port identity changed", err)
	}
	if _, err := rssTime("Wed, 30 Sep 2026 08:00:00 CST"); err == nil {
		t.Fatal("ambiguous timezone accepted as UTC")
	}
}
