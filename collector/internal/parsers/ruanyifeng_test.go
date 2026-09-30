package parsers

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
)

func parserClaim() client.RunClaim {
	c := client.RunClaim{}
	c.Run.RunID = "fictional-run"
	c.Run.SourceID = "cn-ruanyifeng"
	c.Run.BusinessDate = "2026-09-30"
	c.Run.ConfigVersion = 1
	c.Run.Generation = 1
	c.Run.FetchMode = "daily_delta"
	c.Run.CollectionReason = "daily"
	c.Run.WindowStart = "2026-09-29T16:00:00.000000Z"
	c.Run.WindowEnd = "2026-09-30T16:00:00.000000Z"
	c.Config.SourceID = c.Run.SourceID
	c.Config.ConfigVersion = 1
	c.Config.Config.ParserVersion = "cn-ruanyifeng-v1"
	c.Config.Config.EntryURLs = []string{"https://example.invalid/feed"}
	c.Config.Config.AllowedHosts = []string{"example.invalid"}
	c.Config.Config.ValidUntil = "2027-01-01T00:00:00.000000Z"
	c.Config.Config.MaxItems = 10
	c.Config.Config.MaxResponseBytes = 2097152
	token, until := "fictional-token", "2026-10-01T12:00:00.000000Z"
	c.ClaimToken = &token
	c.LeaseUntil = &until
	c.CurrentLeaseValid = true
	return c
}
func entry(id, published, updated, content string) string {
	return `<entry><id>` + id + `</id><title type="html">Fictional &lt;b&gt;title&lt;/b&gt;</title><link rel="alternate" href="https://example.invalid/post/1"/><published>` + published + `</published><updated>` + updated + `</updated><author><name>Fictional Author</name></author><category term="fixture"/><content type="html"><![CDATA[` + content + `]]></content></entry>`
}
func atom(entries string) fetcher.Result {
	return fetcher.Result{StatusCode: 200, ContentType: "application/atom+xml", FinalURL: "https://example.invalid/feed", Body: []byte(`<feed xmlns="http://www.w3.org/2005/Atom">` + entries + `</feed>`)}
}
func TestRuanyifengSourceIdentityTimesAndCleanContent(t *testing.T) {
	c := parserClaim()
	a := Ruanyifeng()
	page, err := a.Parse(c, atom(entry("tag:example.invalid,2026:one", "2026-09-30T08:00:00+08:00", "2026-09-30T09:00:00+08:00", `<p>First &amp; second</p><script>secret()</script><p>Third<img src="https://example.invalid/a"/></p>`)))
	if err != nil || len(page.Items) != 1 || !page.End {
		t.Fatalf("parse: %v", err)
	}
	item := page.Items[0]
	if item.SourceItemID != "tag:example.invalid,2026:one" || item.EvidenceTime.Format(time.RFC3339) != "2026-09-30T01:00:00Z" {
		t.Fatal("source identity/time changed")
	}
	var article Article
	if err := json.Unmarshal(item.Payload, &article); err != nil {
		t.Fatal(err)
	}
	if article.PublishedAt == nil || *article.PublishedAt != "2026-09-30T00:00:00.000000Z" || article.BodyText == nil || strings.Contains(*article.BodyText, "secret") || !strings.Contains(*article.BodyText, "First & second") || article.ContentStatus != "full" || len(article.Authors) != 1 {
		t.Fatalf("normalization: %+v", article)
	}
	at := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	record, err := a.Freeze(c, item, at)
	if err != nil {
		t.Fatal(err)
	}
	wire, err := client.DecodeObservation(record.Payload)
	if err != nil || wire.SourceItemID != item.SourceItemID || wire.PayloadHash != record.PayloadHash {
		t.Fatalf("invalid frozen protocol: %v", err)
	}
	if strings.Contains(string(record.Payload), "Fictional Author") || strings.Contains(string(record.Payload), "<script>") {
		t.Fatal("local metadata leaked into wire whitelist")
	}
	if !strings.Contains(strings.Join(page.Gaps, ","), "feed_coverage_unproven") {
		t.Fatal("finite feed claimed full coverage")
	}
}
func TestRuanyifengMissingIdentityTimeAndUnsupportedContentLeaveGaps(t *testing.T) {
	c := parserClaim()
	a := Ruanyifeng()
	missingID := entry("", "2026-09-30T00:00:00Z", "2026-09-30T01:00:00Z", "body")
	missingTime := entry("unknown-time", "", "", "body")
	invalidTime := entry("invalid-time", "not-a-date", "", "body")
	page, err := a.Parse(c, atom(missingID+missingTime+invalidTime+entry("valid", "2026-09-30T00:00:00Z", "2026-09-30T01:00:00Z", "body")))
	if err != nil || len(page.Items) != 1 || len(page.Gaps) < 4 {
		t.Fatalf("bad entries hid valid one or missing evidence: %v %+v", err, page)
	}
	external := strings.Replace(entry("external", "2026-09-30T00:00:00Z", "2026-09-30T01:00:00Z", "body"), `<content type="html">`, `<content type="html" src="https://example.invalid/external">`, 1)
	page, err = a.Parse(c, atom(external))
	if err != nil || len(page.Items) != 1 {
		t.Fatal(err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.ContentStatus != "unavailable" || article.BodyText != nil {
		t.Fatal("external body treated as fetched content")
	}
}
func TestRuanyifengDTDNamespacesMalformedXMLAndUnsafePagingRefused(t *testing.T) {
	a := Ruanyifeng()
	c := parserClaim()
	for _, bad := range []string{`<!DOCTYPE feed [<!ENTITY x SYSTEM "file:///fictional">]><feed xmlns="http://www.w3.org/2005/Atom"/>`, `<feed xmlns="urn:wrong"/>`, `<feed xmlns="http://www.w3.org/2005/Atom"><entry>`, `<feed xmlns="http://www.w3.org/2005/Atom"/><feed xmlns="http://www.w3.org/2005/Atom"/>`} {
		response := atom("")
		response.Body = []byte(bad)
		if _, err := a.Parse(c, response); err == nil {
			t.Fatal("unsafe XML accepted")
		}
	}
	if _, err := a.Request(c, "https://other.invalid/feed"); err == nil {
		t.Fatal("foreign paging allowed")
	}
	page, err := a.Parse(c, atom(`<link rel="next" href="https://other.invalid/page"/>`))
	if err == nil && page.NextCursor != "" {
		t.Fatal("foreign next link followed")
	}
	page, err = a.Parse(c, atom(`<link rel="next" href="/page2"/>`))
	if err != nil || page.End || page.NextCursor != "https://example.invalid/page2" {
		t.Fatal("safe paging lost", err)
	}
}
func TestRuanyifengRepeatedIDSelectsNewestVersionAndTruncates(t *testing.T) {
	c := parserClaim()
	a := Ruanyifeng()
	body := strings.Repeat("中😀", 30000)
	page, err := a.Parse(c, atom(entry("same", "2026-09-30T00:00:00Z", "2026-09-30T01:00:00Z", "old")+entry("same", "2026-09-30T00:00:00Z", "2026-09-30T02:00:00Z", body)))
	if err != nil || len(page.Items) != 1 {
		t.Fatal("duplicate/version mismatch", err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if !article.BodyTruncated || article.ContentStatus != "partial" || len([]rune(*article.BodyText)) > 50000 || len(*article.BodyText) > 200*1024 || len(article.RawSelectedContent) > 200*1024 {
		t.Fatal("text/raw UTF-8 bounds not enforced")
	}
}

type singleFeed struct{ response fetcher.Result }

func (s singleFeed) Fetch(context.Context, fetcher.Request) (fetcher.Result, error) {
	return s.response, nil
}
func TestRuanyifengSchedulerWindowAndSQLiteResume(t *testing.T) {
	c := parserClaim()
	a := Ruanyifeng()
	now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
	path := filepath.Join(t.TempDir(), "fixture.db")
	store, err := outbox.Open(path, "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.PersistClaim(c); err != nil {
		t.Fatal(err)
	}
	response := atom(entry("inside", "2026-09-30T00:00:00Z", "2026-09-30T01:00:00Z", "body") + entry("outside", "2026-10-01T00:00:00Z", "2026-10-01T01:00:00Z", "body"))
	s, err := scheduler.New(singleFeed{response}, store, a, now, 2, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	result, err := s.Run(context.Background(), c)
	if err != nil || result.Observations != 1 || !result.Finished {
		t.Fatalf("window: %v %+v", err, result)
	}
	result, err = s.Run(context.Background(), c)
	if err != nil || result.Observations != 0 {
		t.Fatalf("resume duplicated: %v %+v", err, result)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal(err)
	}
}
