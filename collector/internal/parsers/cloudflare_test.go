package parsers

import (
	"encoding/json"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestCloudflareSourceBodyBoundsAndIdentityConflict(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = "global-cloudflare"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "global-cloudflare-v1"
	a := Cloudflare()
	row := `<item xmlns:content="http://purl.org/rss/1.0/modules/content/"><guid>fictional-cloud-1</guid><title>Fictional cloud post</title><link>https://example.invalid/cloud</link><pubDate>Wed, 30 Sep 2026 08:00:00 +0800</pubDate><description>summary</description><content:encoded><![CDATA[<p>` + strings.Repeat("界", 51000) + `</p><img src="https://example.invalid/attachment"/><iframe>hidden</iframe>]]></content:encoded></item>`
	page, err := a.Parse(c, rss(row))
	if err != nil || len(page.Items) != 1 {
		t.Fatal(err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText == nil || utf8.RuneCountInString(*article.BodyText) != 50000 || !article.BodyTruncated || article.ContentStatus != "partial" || strings.Contains(*article.BodyText, "hidden") {
		t.Fatal("body limit or cleanup broken")
	}
	page, err = a.Parse(c, rss(row+row))
	if err != nil || len(page.Items) != 1 {
		t.Fatal("duplicate feed entries not deduplicated")
	}
	page, err = a.Parse(c, rss(row+strings.Replace(row, "Fictional cloud post", "Changed fictional cloud post", 1)))
	if err != nil || len(page.Items) != 0 || !strings.Contains(strings.Join(page.Gaps, ","), "conflicting_entry_identity") {
		t.Fatal("ambiguous identity accepted")
	}
	c.Config.Config.ParserVersion = "other-v1"
	if _, err = a.Request(c, ""); err == nil {
		t.Fatal("unknown profile accepted")
	}
}
