package parsers

import (
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"strings"
	"testing"
)

func TestVueUnicodeReleaseUnknownFieldsAndWrongSource(t *testing.T) {
	c := releaseClaim("global-vue")
	a := Vue()
	cursor := releaseStart(t, a, c)
	req, err := a.Request(c, cursor)
	if err != nil {
		t.Fatal(err)
	}
	row := fictionalRelease(77104, "2026-09-30T08:00:00+08:00", "# 虚构发布\n\n新增 **组合式** 工具。")
	row = strings.TrimSuffix(row, "}") + `,"future_upstream_field":{"supported":true}}`
	page, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte("[" + row + "]")})
	if err != nil || len(page.Items) != 1 {
		t.Fatal(err)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText == nil || !strings.Contains(*article.BodyText, "新增 组合式 工具") || page.Items[0].SourceItemID != "99102:77104" {
		t.Fatalf("normalization %+v", article)
	}
	wrong := releaseClaim("global-react")
	if _, err = a.Request(wrong, ""); err == nil {
		t.Fatal("cross-source claim accepted")
	}
}
