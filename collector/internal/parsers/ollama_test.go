package parsers

import (
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestOllamaPreviewExclusionBodyBoundsAndAssetsIgnored(t *testing.T) {
	c := releaseClaim("global-ollama")
	a := Ollama()
	cursor := releaseStart(t, a, c)
	req, err := a.Request(c, cursor)
	if err != nil {
		t.Fatal(err)
	}
	row := fictionalRelease(77106, "2026-09-30T00:00:00Z", strings.Repeat("界", 80000))
	preview := strings.Replace(row, `"prerelease":false`, `"prerelease":true`, 1)
	row = strings.TrimSuffix(row, "}") + `,"assets":[{"browser_download_url":"https://example.invalid/fictional-model.bin"}]}`
	page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte("[" + row + "," + preview + "]")})
	if err != nil || len(page.Items) != 1 {
		t.Fatal(err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText == nil || utf8.RuneCountInString(*article.BodyText) != 50000 || len(article.RawSelectedContent) > 200*1024 || !article.BodyTruncated || article.ContentStatus != "partial" || strings.Contains(string(page.Items[0].Payload), "fictional-model.bin") {
		t.Fatal("body boundary or asset leak")
	}
}
