package parsers

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
)

func TestIthomeDevelopmentRangeAndBoundaryWords(t *testing.T) {
	for _, tc := range []struct {
		title, summary string
		include        bool
	}{
		{"某编程语言发布新版本与标准库变化", "", true},
		{"某 IDE 新增调试 API", "", true},
		{"模型服务发布新版本", "新的推理 API 和开发 SDK", true},
		{"手机新品与优惠促销", "", false},
		{"操作系统更新", "新增开发接口与 SDK", true},
		{"Windows 全新外观", "AI 功能和硬件新品", false},
		{"模型发布新版本", "成绩更高但无开发接口", false},
		{"游戏更新", "新增活动与优惠", false},
		{"游戏开发 SDK 发布", "", true},
		{"IDE 优惠促销", "SDK API", false},
		{"Capital hardware design", "", false},
		{"Fictional Python 3 发布", "", true},
		{"新服务开放API接口", "", true},
	} {
		if got := developmentTopic(tc.title, tc.summary); got != tc.include {
			t.Fatalf("topic %q: %v", tc.title, got)
		}
	}
}
func TestIthomeFiltersCleanedTitleSummaryBeforeObservations(t *testing.T) {
	c := parserClaim()
	c.Run.SourceID = "cn-ithome"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "cn-ithome-v1"
	body := `<item><title>Fictional IDE 发布</title><link>https://example.invalid/dev</link><pubDate>Wed, 30 Sep 2026 08:00:00 +0800</pubDate><description><![CDATA[<p>新增开发 SDK</p>]]></description></item>` +
		`<item><title>手机新品</title><link>https://example.invalid/phone</link><pubDate>Wed, 30 Sep 2026 08:00:00 +0800</pubDate><description><![CDATA[<script>API SDK</script><p>硬件更新</p>]]></description></item>` +
		`<item><title>Fictional SDK 错误时间</title><link>https://example.invalid/bad</link><pubDate>unknown</pubDate></item>`
	page, err := Ithome().Parse(c, rss(body))
	if err != nil || len(page.Items) != 1 || !strings.HasSuffix(page.Items[0].SourceItemID, "/dev") {
		t.Fatalf("filter parse: %v %+v", err, page)
	}
	var article Article
	json.Unmarshal(page.Items[0].Payload, &article)
	if article.BodyText != nil || article.ContentStatus != "unavailable" {
		t.Fatal("description claimed as full body")
	}
	if !strings.Contains(strings.Join(page.Gaps, ","), "entry_time_unknown") {
		t.Fatal("bad fields hidden by topic filter")
	}
	request, err := Ithome().Request(c, "")
	if err != nil || request.Format != fetcher.RSS {
		t.Fatal(err)
	}
}
