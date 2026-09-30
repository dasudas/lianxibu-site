package parsers

import (
	"strings"
	"unicode"

	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
)

func Ithome() *FeedAdapter {
	return &FeedAdapter{sourceID: "cn-ithome", version: "cn-ithome-v1", format: fetcher.RSS, onlyDevelopment: true}
}

// This closed policy is part of cn-ithome-v1. Changes require a parser version bump.
func developmentTopic(title, summary string) bool {
	for _, term := range []string{"促销", "优惠", "首销", "降价", "打折", "折扣"} {
		if strings.Contains(title, term) {
			return false
		}
	}
	text := strings.ToLower(title + " " + summary)
	for _, term := range []string{"无开发接口", "没有开发接口", "不涉及开发"} {
		text = strings.ReplaceAll(text, term, "")
	}
	for _, term := range []string{"编程语言", "标准库", "开发框架", "开发工具", "开发者工具", "开发接口", "开发套件", "开发安全", "代码安全", "代码托管", "代码发布", "代码编辑器", "集成开发环境", "调试器", "编译器", "软件开发", "游戏开发", "模型接口", "模型 api"} {
		if strings.Contains(text, term) {
			return true
		}
	}
	words := strings.FieldsFunc(text, func(r rune) bool { return !unicode.Is(unicode.Latin, r) && !unicode.IsDigit(r) && r != '_' })
	for _, word := range words {
		switch word {
		case "sdk", "api", "ide", "github", "gitlab", "vscode", "python", "javascript", "typescript", "java", "golang", "rust", "kotlin", "swift", "react", "vue", "angular", "django", "spring", "nodejs", "npm", "pypi":
			return true
		}
	}
	return false
}
