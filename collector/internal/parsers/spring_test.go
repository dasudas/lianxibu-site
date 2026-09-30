package parsers

import (
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"strings"
	"testing"
)

func TestSpringSourcePublishedTimeMissingIDAndBody(t *testing.T) {
	c := releaseClaim("global-spring")
	a := Spring()
	cursor := releaseStart(t, a, c)
	req, err := a.Request(c, cursor)
	if err != nil {
		t.Fatal(err)
	}
	row := fictionalRelease(77105, "2026-09-30T08:00:00+08:00", "")
	bad := strings.Replace(row, `"id":77105`, `"id":0`, 1)
	row = strings.TrimSuffix(row, "}") + `,"created_at":"2019-01-01T00:00:00Z"}`
	page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte("[" + row + "," + bad + "]")})
	if err != nil || len(page.Items) != 1 {
		t.Fatalf("parse %v %+v", err, page)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.PublishedAt == nil || *article.PublishedAt != "2026-09-30T00:00:00.000000Z" || article.BodyText != nil || article.ContentStatus != "unavailable" || article.UpdatedAt != nil {
		t.Fatalf("source time/body %+v", article)
	}
	if !strings.Contains(strings.Join(page.Gaps, ","), "release_fields_invalid") {
		t.Fatal("missing identity hidden")
	}
}
