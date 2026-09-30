package parsers

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
)

func TestCnblogsAtomRedirectXHTMLAndStableIdentity(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = "cn-cnblogs"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "cn-cnblogs-v1"
	c.Config.Config.EntryURLs = []string{"https://www.cnblogs.com/rss"}
	c.Config.Config.AllowedHosts = []string{"www.cnblogs.com", "feed.cnblogs.com"}
	a := Cnblogs()
	request, err := a.Request(c, "")
	if err != nil || request.Format != fetcher.Atom {
		t.Fatal(err)
	}
	response := atom(entry("urn:fictional:post", "2026-09-30T00:00:00Z", "2026-09-30T01:00:00Z", "body"))
	response.FinalURL = "https://feed.cnblogs.com/blog/sitehome/rss"
	response.Body = []byte(strings.Replace(string(response.Body), `<content type="html"><![CDATA[body]]></content>`, `<content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Fictional <b>content</b></p><script>bad()</script></div></content>`, 1))
	page, err := a.Parse(c, response)
	if err != nil || len(page.Items) != 1 || page.Items[0].SourceItemID != "urn:fictional:post" {
		t.Fatalf("redirect Atom parse: %v", err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText == nil || *article.BodyText != "Fictional content" || article.ContentStatus != "full" {
		t.Fatalf("XHTML content: %+v", article)
	}
	c.Config.Config.AllowedHosts = []string{"www.cnblogs.com"}
	if _, err := a.Parse(c, response); err == nil {
		t.Fatal("unapproved redirect accepted")
	}
}
func TestCnblogsMissingPublishedDoesNotUseRetrievalTime(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = "cn-cnblogs"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "cn-cnblogs-v1"
	response := atom(entry("urn:fictional:post", "", "2026-09-30T01:00:00Z", "body"))
	page, err := Cnblogs().Parse(c, response)
	if err != nil || len(page.Items) != 1 {
		t.Fatal(err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.PublishedAt != nil || article.TimeBasis != "updated" || article.UpdatedAt == nil {
		t.Fatal("unknown publication date fabricated")
	}
	c.Config.Config.ParserVersion = "wrong-version"
	if _, err := Cnblogs().Parse(c, response); err == nil {
		t.Fatal("unknown parser version accepted")
	}
}
