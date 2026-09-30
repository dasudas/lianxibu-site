package parsers

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestSimonHTMLSummaryIsSourceBodyAndContentWins(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = "global-simon"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "global-simon-v1"
	a := Simon()
	original := `<entry><id>tag:example.invalid,2026:summary</id><title>Fictional summary post</title><link href="https://example.invalid/post"/><published>2026-09-30T00:00:00Z</published><summary type="html"><![CDATA[<p>Source body</p><script>ignore()</script><img src="https://example.invalid/image"/>]]></summary></entry>`
	page, err := a.Parse(c, atom(original))
	if err != nil || len(page.Items) != 1 {
		t.Fatalf("parse: %v", err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText == nil || *article.BodyText != "Source body" || article.ContentStatus != "full" || article.RawSelectedContent == "" || article.SourceSummary == nil {
		t.Fatalf("summary body: %+v", article)
	}
	if _, err := a.Freeze(c, page.Items[0], time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	withContent := strings.Replace(original, "</entry>", `<content type="html">Preferred body</content></entry>`, 1)
	page, err = a.Parse(c, atom(withContent))
	if err != nil || len(page.Items) != 1 {
		t.Fatal(err)
	}
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText == nil || *article.BodyText != "Preferred body" {
		t.Fatal("summary replaced explicit content")
	}
	plain := strings.Replace(original, `summary type="html"`, `summary type="text"`, 1)
	page, err = a.Parse(c, atom(plain))
	if err != nil || len(page.Items) != 1 {
		t.Fatal(err)
	}
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText != nil {
		t.Fatal("unsupported summary promoted to body")
	}
	broken := strings.Replace(original, "2026-09-30T00:00:00Z", "unknown", 1)
	page, err = a.Parse(c, atom(broken))
	if err != nil || len(page.Items) != 0 || len(page.Gaps) < 2 {
		t.Fatal("missing source time hidden")
	}
}
