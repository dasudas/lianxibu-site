package parsers

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestGitHubBlogNamespacedBodyAuthorIdentityAndFallback(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = "global-github-blog"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "global-github-blog-v1"
	a := GitHubBlog()
	raw := `<rss xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><item><guid isPermaLink="false">fictional-post-51</guid><title>Fictional changelog</title><link>https://example.invalid/changelog</link><pubDate>Wed, 30 Sep 2026 08:00:00 +0800</pubDate><description>Short summary</description><dc:creator>Fictional Author</dc:creator><content:encoded><![CDATA[<p>Complete source body</p><script>ignore()</script><img src="https://example.invalid/img"/>]]></content:encoded></item></channel></rss>`
	response := rss("")
	response.Body = []byte(raw)
	page, err := a.Parse(c, response)
	if err != nil || len(page.Items) != 1 || page.Items[0].SourceItemID != "fictional-post-51" {
		t.Fatalf("parse %v %+v", err, page)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText == nil || *article.BodyText != "Complete source body" || article.SourceSummary == nil || *article.SourceSummary != "Short summary" || len(article.Authors) != 1 || article.ContentStatus != "full" || !strings.Contains(article.RawSelectedContent, "Complete source body") {
		t.Fatalf("body metadata %+v", article)
	}
	if _, err := a.Freeze(c, page.Items[0], time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	response.Body = []byte(strings.ReplaceAll(strings.Replace(raw, `<guid isPermaLink="false">fictional-post-51</guid>`, "", 1), "http://purl.org/rss/1.0/modules/content/", "https://wrong.invalid/namespace"))
	page, err = a.Parse(c, response)
	if err != nil || len(page.Items) != 1 || page.Items[0].SourceItemID != "https://example.invalid/changelog" {
		t.Fatal("fallback identity missing")
	}
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText != nil || article.ContentStatus != "unavailable" {
		t.Fatal("wrong namespace accepted as full body")
	}
}
