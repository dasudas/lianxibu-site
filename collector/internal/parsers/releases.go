package parsers

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"github.com/yuin/goldmark"
)

type releaseCursor struct {
	Run          string `json:"run"`
	Source       string `json:"source"`
	Config       uint64 `json:"config"`
	Entry        string `json:"entry"`
	RepositoryID int64  `json:"repository_id"`
	Page         int    `json:"page"`
}
type releasePending struct {
	cursor releaseCursor
	url    string
}

// Request restores all normalization context from the persisted, run-bound cursor.
// No repository state is inferred from names, release assets, or process memory alone.
type ReleasesAdapter struct {
	feed    *FeedAdapter
	mu      sync.Mutex
	pending map[string]releasePending
}

var _ scheduler.Adapter = (*ReleasesAdapter)(nil)

func React() *ReleasesAdapter { return newReleases("global-react") }
func Vue() *ReleasesAdapter   { return newReleases("global-vue") }
func newReleases(source string) *ReleasesAdapter {
	return &ReleasesAdapter{feed: &FeedAdapter{sourceID: source, version: source + "-v1", format: fetcher.JSON}, pending: map[string]releasePending{}}
}
func releaseEntry(c client.RunClaim) (*url.URL, error) {
	checked, err := sourceURL(c.Config.Config.EntryURLs[0], "", c.Config.Config.AllowedHosts)
	if err != nil {
		return nil, err
	}
	u, _ := url.Parse(checked)
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	if u.Hostname() != "api.github.com" || len(parts) != 4 || parts[0] != "repos" || parts[3] != "releases" || !repoPart(parts[1]) || !repoPart(parts[2]) || u.Path != "/"+strings.Join(parts, "/") || u.RawPath != "" || u.Fragment != "" {
		return nil, ErrSourceConfig
	}
	for key := range u.Query() {
		if key != "per_page" && key != "page" {
			return nil, ErrSourceConfig
		}
	}
	return u, nil
}
func repoPart(part string) bool {
	if part == "" || part == "." || part == ".." || len(part) > 100 {
		return false
	}
	for _, r := range part {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' || r == '.') {
			return false
		}
	}
	return true
}
func (a *ReleasesAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	if a == nil || !a.feed.valid(c) || c.Run.RunID == "" {
		return fetcher.Request{}, ErrSourceConfig
	}
	u, err := releaseEntry(c)
	if err != nil {
		return fetcher.Request{}, err
	}
	state := releaseCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: c.Run.ConfigVersion, Entry: c.Config.Config.EntryURLs[0]}
	if cursor != "" {
		if len(cursor) > 4096 || checkedJSON([]byte(cursor)) != nil || json.Unmarshal([]byte(cursor), &state) != nil || state.Run != c.Run.RunID || state.Source != c.Run.SourceID || state.Config != c.Run.ConfigVersion || state.Entry != c.Config.Config.EntryURLs[0] || state.RepositoryID <= 0 || state.Page < 1 || state.Page > 10000 {
			return fetcher.Request{}, ErrSourceConfig
		}
		u.RawQuery = url.Values{"per_page": {"30"}, "page": {strconv.Itoa(state.Page)}}.Encode()
	} else {
		u.Path = strings.TrimSuffix(u.Path, "/releases")
		u.RawQuery = ""
	}
	requestURL := u.String()
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.pending) >= 100 {
		if _, ok := a.pending[c.Run.RunID]; !ok {
			return fetcher.Request{}, ErrSourceConfig
		}
	}
	a.pending[c.Run.RunID] = releasePending{cursor: state, url: requestURL}
	return fetcher.Request{SourceID: c.Run.SourceID, URL: requestURL, AllowedHosts: append([]string(nil), c.Config.Config.AllowedHosts...), Format: fetcher.JSON, MinInterval: time.Second}, nil
}
func (a *ReleasesAdapter) Parse(c client.RunClaim, response fetcher.Result) (scheduler.Page, error) {
	if a == nil || !a.feed.valid(c) || response.StatusCode != 200 || checkedJSON(response.Body) != nil {
		return scheduler.Page{}, ErrSourceDocument
	}
	a.mu.Lock()
	pending, exists := a.pending[c.Run.RunID]
	a.mu.Unlock()
	if !exists || response.FinalURL != pending.url || pending.cursor.Config != c.Run.ConfigVersion || pending.cursor.Entry != c.Config.Config.EntryURLs[0] {
		return scheduler.Page{}, ErrSourceDocument
	}
	state := pending.cursor
	if state.RepositoryID == 0 {
		var repo struct {
			ID       int64  `json:"id"`
			FullName string `json:"full_name"`
		}
		if json.Unmarshal(response.Body, &repo) != nil || repo.ID <= 0 {
			return scheduler.Page{}, ErrSourceDocument
		}
		u, _ := url.Parse(pending.url)
		if !strings.EqualFold(repo.FullName, strings.TrimPrefix(u.Path, "/repos/")) {
			return scheduler.Page{}, ErrSourceDocument
		}
		state.RepositoryID = repo.ID
		state.Page = 1
		cursor, _ := json.Marshal(state)
		return scheduler.Page{Items: []scheduler.Item{}, End: false, NextCursor: string(cursor)}, nil
	}
	var entries []json.RawMessage
	if json.Unmarshal(response.Body, &entries) != nil || entries == nil || len(entries) > 30 {
		return scheduler.Page{}, ErrSourceDocument
	}
	page := scheduler.Page{Items: []scheduler.Item{}, End: len(entries) < 30, Gaps: []string{"release_coverage_unproven"}}
	rows := map[string]scheduler.Item{}
	conflicts := map[string]bool{}
	for _, raw := range entries {
		var release struct {
			ID         int64   `json:"id"`
			Name       string  `json:"name"`
			Tag        string  `json:"tag_name"`
			URL        string  `json:"html_url"`
			Draft      *bool   `json:"draft"`
			Prerelease *bool   `json:"prerelease"`
			Published  *string `json:"published_at"`
			Body       *string `json:"body"`
		}
		if json.Unmarshal(raw, &release) != nil || release.ID <= 0 || release.Draft == nil || release.Prerelease == nil {
			page.Gaps = append(page.Gaps, "release_fields_invalid")
			continue
		}
		if *release.Draft || *release.Prerelease {
			continue
		}
		entryURL, _ := releaseEntry(c)
		articleLink, linkErr := url.Parse(release.URL)
		expectedPath := strings.TrimPrefix(strings.TrimSuffix(entryURL.Path, "/releases"), "/repos") + "/releases/"
		if linkErr != nil || articleLink.Hostname() != "github.com" || !strings.HasPrefix(strings.ToLower(articleLink.Path), strings.ToLower(expectedPath)) {
			page.Gaps = append(page.Gaps, "entry_link_invalid")
			continue
		}
		if release.Published == nil || strings.TrimSpace(*release.Published) == "" {
			page.Gaps = append(page.Gaps, "entry_time_unknown")
			continue
		}
		title := strings.TrimSpace(release.Name)
		if title == "" {
			title = release.Tag
		}
		entry := atomEntry{ID: fmt.Sprintf("%d:%d", state.RepositoryID, release.ID), Title: atomText{Text: title}, Published: *release.Published, Links: []atomLink{{Rel: "alternate", Href: release.URL}}}
		selected := ""
		rawTruncated := false
		if release.Body != nil && *release.Body != "" {
			selected, rawTruncated = boundText(*release.Body, 200000, 200*1024)
			var rendered bytes.Buffer
			if err := goldmark.Convert([]byte(selected), &rendered); err != nil {
				page.Gaps = append(page.Gaps, "release_markdown_invalid")
				continue
			}
			entry.Content = atomText{Type: "html", Text: rendered.String()}
		}
		item, gaps, err := a.feed.atomItem(c, entry, pending.url)
		page.Gaps = append(page.Gaps, gaps...)
		if err != nil {
			continue
		}
		var article Article
		json.Unmarshal(item.Payload, &article)
		article.RawSelectedContent = selected
		if release.Tag != "" {
			article.Tags = []string{release.Tag}
		}
		article.BodyTruncated = article.BodyTruncated || rawTruncated
		if article.BodyText != nil && strings.TrimSpace(*article.BodyText) == "" {
			article.BodyText = nil
			article.ContentStatus = "unavailable"
			page.Gaps = append(page.Gaps, "release_body_unavailable")
		}
		if article.BodyTruncated && article.BodyText != nil {
			article.ContentStatus = "partial"
		}
		item.Payload, _ = json.Marshal(article)
		digest := sha256.Sum256(item.Payload)
		item.Version = hex.EncodeToString(digest[:])
		page.Gaps = append(page.Gaps, "release_revision_time_unknown")
		id := item.SourceItemID
		if conflicts[id] {
			continue
		}
		if old, ok := rows[id]; ok {
			if !bytes.Equal(old.Payload, item.Payload) {
				delete(rows, id)
				conflicts[id] = true
				page.Gaps = append(page.Gaps, "conflicting_entry_identity")
			}
			continue
		}
		rows[id] = item
	}
	keys := make([]string, 0, len(rows))
	for key := range rows {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		page.Items = append(page.Items, rows[key])
	}
	if !page.End {
		if state.Page == 10000 {
			page.End = true
			page.Gaps = append(page.Gaps, "release_pagination_limit")
		} else {
			state.Page++
			cursor, _ := json.Marshal(state)
			page.NextCursor = string(cursor)
		}
	}
	if page.End {
		a.mu.Lock()
		delete(a.pending, c.Run.RunID)
		a.mu.Unlock()
	}
	return page, nil
}
func (a *ReleasesAdapter) Freeze(c client.RunClaim, item scheduler.Item, at time.Time) (outbox.Record, error) {
	return a.feed.Freeze(c, item, at)
}

// Reject duplicate object fields, excessive nesting, malformed/trailing JSON, and invalid UTF-8.
// Unknown fields remain allowed for additive upstream changes.
func checkedJSON(raw []byte) error {
	if len(raw) == 0 || len(raw) > 2*1024*1024 || !utf8.Valid(raw) {
		return ErrSourceDocument
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var read func(int) error
	read = func(depth int) error {
		if depth > 64 {
			return ErrSourceDocument
		}
		token, err := decoder.Token()
		if err != nil {
			return err
		}
		delim, ok := token.(json.Delim)
		if !ok {
			return nil
		}
		switch delim {
		case '{':
			seen := map[string]bool{}
			for decoder.More() {
				key, err := decoder.Token()
				if err != nil {
					return err
				}
				name, ok := key.(string)
				if !ok || seen[name] {
					return ErrSourceDocument
				}
				seen[name] = true
				if err := read(depth + 1); err != nil {
					return err
				}
			}
			last, err := decoder.Token()
			if err != nil || last != json.Delim('}') {
				return ErrSourceDocument
			}
		case '[':
			for decoder.More() {
				if err := read(depth + 1); err != nil {
					return err
				}
			}
			last, err := decoder.Token()
			if err != nil || last != json.Delim(']') {
				return ErrSourceDocument
			}
		default:
			return ErrSourceDocument
		}
		return nil
	}
	if err := read(0); err != nil {
		return ErrSourceDocument
	}
	if _, err := decoder.Token(); err != io.EOF {
		return ErrSourceDocument
	}
	return nil
}
