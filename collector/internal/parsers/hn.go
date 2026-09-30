package parsers

import (
	"encoding/json"
	"fmt"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const hnListURL = "https://hacker-news.firebaseio.com/v0/topstories.json"

type hnCursor struct {
	Run     string   `json:"run"`
	Source  string   `json:"source"`
	Config  uint64   `json:"config"`
	Entry   string   `json:"entry"`
	IDs     []int64  `json:"ids"`
	Index   int      `json:"index"`
	Started bool     `json:"started"`
	Gaps    []string `json:"gaps"`
}
type hnPending struct {
	state hnCursor
	url   string
}
type HNAdapter struct {
	feed    *FeedAdapter
	mu      sync.Mutex
	pending map[string]hnPending
}

var _ scheduler.Adapter = (*HNAdapter)(nil)
var _ scheduler.CompletedCursor = (*HNAdapter)(nil)

func HackerNews() *HNAdapter {
	return &HNAdapter{feed: &FeedAdapter{sourceID: "global-hn", version: "global-hn-v1", format: fetcher.JSON}, pending: map[string]hnPending{}}
}
func (a *HNAdapter) state(c client.RunClaim, cursor string) (hnCursor, error) {
	if a == nil || !a.feed.valid(c) || c.Run.RunID == "" || c.Config.Config.EntryURLs[0] != hnListURL {
		return hnCursor{}, ErrSourceConfig
	}
	if _, err := sourceURL(hnListURL, "", c.Config.Config.AllowedHosts); err != nil {
		return hnCursor{}, err
	}
	if len(c.Config.Config.EntryURLs) > 2 || len(c.Config.Config.EntryURLs) == 2 && c.Config.Config.EntryURLs[1] != "https://hacker-news.firebaseio.com/v0/item/{id}.json" {
		return hnCursor{}, ErrSourceConfig
	}
	state := hnCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: c.Run.ConfigVersion, Entry: hnListURL, IDs: []int64{}, Gaps: []string{"hn_topstories_coverage_unproven"}}
	if cursor == "" {
		return state, nil
	}
	decoder := json.NewDecoder(strings.NewReader(cursor))
	decoder.DisallowUnknownFields()
	if len(cursor) > 4096 || checkedJSON([]byte(cursor)) != nil || decoder.Decode(&state) != nil ||
		state.Run != c.Run.RunID || state.Source != c.Run.SourceID || state.Config != c.Run.ConfigVersion || state.Entry != hnListURL ||
		!state.Started || state.IDs == nil || len(state.IDs) > 30 || state.Index < 0 || state.Index > len(state.IDs) || len(state.Gaps) > 32 {
		return hnCursor{}, ErrSourceConfig
	}
	seen := map[int64]bool{}
	for _, id := range state.IDs {
		if id <= 0 || id > 9007199254740991 || seen[id] {
			return hnCursor{}, ErrSourceConfig
		}
		seen[id] = true
	}
	for _, gap := range state.Gaps {
		if !hnGapAllowed(gap) {
			return hnCursor{}, ErrSourceConfig
		}
	}
	return state, nil
}
func (a *HNAdapter) Completed(c client.RunClaim, cursor string) (bool, []string, error) {
	state, err := a.state(c, cursor)
	if err != nil {
		return false, nil, err
	}
	return state.Started && state.Index == len(state.IDs), append([]string(nil), state.Gaps...), nil
}
func (a *HNAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	state, err := a.state(c, cursor)
	if err != nil {
		return fetcher.Request{}, err
	}
	target := hnListURL
	if state.Started {
		if state.Index == len(state.IDs) {
			return fetcher.Request{}, ErrSourceConfig
		}
		target = fmt.Sprintf("https://hacker-news.firebaseio.com/v0/item/%d.json", state.IDs[state.Index])
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.pending) >= 100 {
		if _, exists := a.pending[c.Run.RunID]; !exists {
			return fetcher.Request{}, ErrSourceConfig
		}
	}
	a.pending[c.Run.RunID] = hnPending{state: state, url: target}
	return fetcher.Request{SourceID: c.Run.SourceID, URL: target, AllowedHosts: append([]string(nil), c.Config.Config.AllowedHosts...), Format: fetcher.JSON, MinInterval: time.Second}, nil
}
func (a *HNAdapter) Parse(c client.RunClaim, response fetcher.Result) (scheduler.Page, error) {
	if a == nil || !a.feed.valid(c) || response.StatusCode != 200 || checkedJSON(response.Body) != nil {
		return scheduler.Page{}, ErrSourceDocument
	}
	a.mu.Lock()
	pending, ok := a.pending[c.Run.RunID]
	a.mu.Unlock()
	if !ok || response.FinalURL != pending.url || pending.state.Config != c.Run.ConfigVersion || c.Config.Config.EntryURLs[0] != pending.state.Entry {
		return scheduler.Page{}, ErrSourceDocument
	}
	state := pending.state
	items := []scheduler.Item{}
	if !state.Started {
		var ids []int64
		if json.Unmarshal(response.Body, &ids) != nil || ids == nil || len(ids) > 500 {
			return scheduler.Page{}, ErrSourceDocument
		}
		seen := map[int64]bool{}
		for _, id := range ids {
			if id <= 0 || id > 9007199254740991 {
				state.Gaps = hnGap(state.Gaps, "hn_list_invalid_id")
				continue
			}
			if seen[id] {
				continue
			}
			seen[id] = true
			if len(state.IDs) == 30 {
				state.Gaps = hnGap(state.Gaps, "hn_detail_limit")
				break
			}
			state.IDs = append(state.IDs, id)
		}
		state.Started = true
	} else {
		item, gaps := hnItem(state.IDs[state.Index], response.Body)
		for _, gap := range gaps {
			state.Gaps = hnGap(state.Gaps, gap)
		}
		if item != nil {
			items = append(items, *item)
		}
		state.Index++
	}
	cursor, err := json.Marshal(state)
	if err != nil || len(cursor) > 4096 {
		return scheduler.Page{}, ErrSourceDocument
	}
	end := state.Started && state.Index == len(state.IDs)
	if end {
		a.mu.Lock()
		delete(a.pending, c.Run.RunID)
		a.mu.Unlock()
	}
	return scheduler.Page{Items: items, NextCursor: string(cursor), End: end, Gaps: append([]string(nil), state.Gaps...)}, nil
}
func hnGapAllowed(gap string) bool {
	switch gap {
	case "hn_topstories_coverage_unproven", "hn_detail_limit", "hn_list_invalid_id", "hn_item_missing", "hn_entry_fields_invalid", "entry_time_unknown", "entry_time_invalid", "hn_link_fallback", "hn_summary_truncated", "hn_text_truncated", "hn_title_invalid":
		return true
	}
	return false
}
func hnGap(gaps []string, gap string) []string {
	for _, old := range gaps {
		if old == gap {
			return gaps
		}
	}
	return append(gaps, gap)
}

func hnItem(expected int64, raw []byte) (*scheduler.Item, []string) {
	if strings.TrimSpace(string(raw)) == "null" {
		return nil, []string{"hn_item_missing"}
	}
	var value struct {
		ID      int64  `json:"id"`
		Type    string `json:"type"`
		Deleted bool   `json:"deleted"`
		Dead    bool   `json:"dead"`
		Title   string `json:"title"`
		URL     string `json:"url"`
		Text    string `json:"text"`
		By      string `json:"by"`
		Time    *int64 `json:"time"`
	}
	if json.Unmarshal(raw, &value) != nil || value.ID != expected {
		return nil, []string{"hn_entry_fields_invalid"}
	}
	if value.Deleted || value.Dead {
		return nil, nil
	}
	if value.Type == "" {
		return nil, []string{"hn_entry_fields_invalid"}
	}
	if value.Type != "story" {
		return nil, nil
	}
	if value.Time == nil {
		return nil, []string{"entry_time_unknown"}
	}
	if *value.Time <= 0 || *value.Time > 253402300799 {
		return nil, []string{"entry_time_invalid"}
	}
	submitted := time.Unix(*value.Time, 0).UTC()
	stamp := wireTime(submitted)
	title := strings.Join(strings.Fields(htmlText(value.Title)), " ")
	if title == "" || utf8.RuneCountInString(title) > 512 {
		return nil, []string{"hn_title_invalid"}
	}
	gaps := []string{}
	article := Article{Title: title, SourceURL: fmt.Sprintf("https://news.ycombinator.com/item?id=%d", expected), SubmittedAt: &stamp, TimeBasis: "submitted", ContentStatus: "unavailable"}
	self := strings.TrimSpace(value.URL) == ""
	plain := strings.Join(strings.Fields(htmlText(value.Text)), " ")
	if !self {
		if external, err := articleURL(value.URL, ""); err == nil {
			article.SourceURL = external
		} else {
			gaps = append(gaps, "hn_link_fallback")
			u, e := url.Parse(value.URL)
			if e == nil && u.Scheme == "http" && u.Hostname() != "" && u.User == nil && len(value.URL) <= 2000 {
				plain = strings.TrimSpace(plain + " 链接: " + value.URL)
			}
		}
	}
	if plain != "" {
		summary, truncated := boundText(plain, 16384, 64*1024)
		article.SourceSummary = &summary
		if truncated {
			gaps = append(gaps, "hn_summary_truncated")
		}
	}
	if self && strings.TrimSpace(value.Text) != "" {
		bounded, rawTruncated := boundText(value.Text, 200000, 200*1024)
		article.RawSelectedContent = bounded
		body, truncated := boundText(strings.Join(strings.Fields(htmlText(bounded)), " "), 50000, 200*1024)
		article.BodyText = &body
		article.BodyTruncated = truncated || rawTruncated
		article.ContentStatus = "full"
		if article.BodyTruncated {
			article.ContentStatus = "partial"
			gaps = append(gaps, "hn_text_truncated")
		}
	}
	if author := strings.TrimSpace(value.By); author != "" {
		name, _ := boundText(author, 256, 1024)
		article.Authors = []string{name}
	}
	payload, _ := json.Marshal(article)
	version, err := client.Hash(client.ArticleProjectionHash, map[string]any{"title": article.Title, "source_url": article.SourceURL, "submitted_at": stamp, "source_summary": hnText(article.SourceSummary), "body_text": hnText(article.BodyText), "content_status": article.ContentStatus, "body_truncated": article.BodyTruncated})
	if err != nil {
		return nil, []string{"hn_entry_fields_invalid"}
	}
	return &scheduler.Item{SourceItemID: strconv.FormatInt(expected, 10), EvidenceTime: submitted, Version: version, EvidenceRef: article.SourceURL, Payload: payload}, gaps
}
func hnText(value *string) any {
	if value == nil {
		return nil
	}
	return *value
}
func (a *HNAdapter) Freeze(c client.RunClaim, item scheduler.Item, at time.Time) (outbox.Record, error) {
	return a.feed.Freeze(c, item, at)
}
