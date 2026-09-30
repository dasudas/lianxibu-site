package parsers

import (
	"bytes"
	"encoding/json"
	"encoding/xml"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type ArxivAdapter struct {
	feed    *FeedAdapter
	mu      sync.Mutex
	pending map[string]arxivPending
}
type arxivCursor struct {
	Run    string   `json:"run"`
	Config uint64   `json:"config"`
	Entry  string   `json:"entry"`
	Offset int      `json:"offset"`
	Done   bool     `json:"done"`
	Gaps   []string `json:"gaps"`
}
type arxivPending struct {
	state arxivCursor
	url   string
}

var arxivPaper = regexp.MustCompile(`^([0-9]{4}\.[0-9]{4,5}|[a-z][a-z0-9.-]*/[0-9]{7})(v[1-9][0-9]{0,5})?$`)
var _ scheduler.Adapter = (*ArxivAdapter)(nil)
var _ scheduler.CompletedCursor = (*ArxivAdapter)(nil)

func Arxiv() *ArxivAdapter {
	return &ArxivAdapter{feed: &FeedAdapter{sourceID: "global-arxiv", version: "global-arxiv-v1", format: fetcher.Atom}, pending: map[string]arxivPending{}}
}
func (a *ArxivAdapter) entry(c client.RunClaim) (string, error) {
	if a == nil || !a.feed.valid(c) || c.Run.RunID == "" || len(c.Config.Config.EntryURLs) != 1 {
		return "", ErrSourceConfig
	}
	raw := c.Config.Config.EntryURLs[0]
	checked, err := sourceURL(raw, "", c.Config.Config.AllowedHosts)
	if err != nil {
		return "", err
	}
	u, _ := url.Parse(checked)
	if u.Hostname() != "export.arxiv.org" || u.Path != "/api/query" || u.RawPath != "" || u.Fragment != "" {
		return "", ErrSourceConfig
	}
	q, e := url.ParseQuery(u.RawQuery)
	if e != nil || len(q) != 5 {
		return "", ErrSourceConfig
	}
	for k, v := range map[string]string{"search_query": "cat:cs.AI", "start": "0", "max_results": "20", "sortBy": "submittedDate", "sortOrder": "descending"} {
		if len(q[k]) != 1 || q.Get(k) != v {
			return "", ErrSourceConfig
		}
	}
	return raw, nil
}
func (a *ArxivAdapter) state(c client.RunClaim, cursor string) (arxivCursor, error) {
	entry, err := a.entry(c)
	if err != nil {
		return arxivCursor{}, err
	}
	if cursor == "" {
		return arxivCursor{Run: c.Run.RunID, Config: c.Run.ConfigVersion, Entry: entry, Gaps: []string{"arxiv_list_coverage_unproven", "arxiv_old_updates_coverage_unproven"}}, nil
	}
	var state arxivCursor
	d := json.NewDecoder(strings.NewReader(cursor))
	d.DisallowUnknownFields()
	if len(cursor) > 4096 || checkedJSON([]byte(cursor)) != nil || d.Decode(&state) != nil || state.Run != c.Run.RunID || state.Config != c.Run.ConfigVersion || state.Entry != entry || state.Offset < 0 || state.Offset > 2000 || !state.Done && (state.Offset == 0 || state.Offset%20 != 0 || state.Offset >= 2000) || len(state.Gaps) > 64 {
		return arxivCursor{}, ErrSourceConfig
	}
	for _, gap := range state.Gaps {
		switch gap {
		case "arxiv_list_coverage_unproven", "arxiv_old_updates_coverage_unproven", "arxiv_page_limit", "arxiv_paging_metadata_missing", "arxiv_identity_invalid", "arxiv_link_invalid", "entry_published_invalid", "entry_updated_invalid", "entry_time_unknown", "entry_time_inconsistent", "entry_title_invalid", "entry_summary_unsupported", "arxiv_revision_unspecified", "conflicting_entry_identity":
		default:
			return arxivCursor{}, ErrSourceConfig
		}
	}
	return state, nil
}
func (a *ArxivAdapter) Completed(c client.RunClaim, cursor string) (bool, []string, error) {
	state, err := a.state(c, cursor)
	if err != nil {
		return false, nil, err
	}
	return state.Done, append([]string(nil), state.Gaps...), nil
}
func (a *ArxivAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	state, err := a.state(c, cursor)
	if err != nil || state.Done {
		return fetcher.Request{}, ErrSourceConfig
	}
	entry := state.Entry
	if state.Offset > 0 {
		u, _ := url.Parse(entry)
		q := u.Query()
		q.Set("start", strconv.Itoa(state.Offset))
		u.RawQuery = q.Encode()
		entry = u.String()
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.pending) >= 100 {
		if _, ok := a.pending[c.Run.RunID]; !ok {
			return fetcher.Request{}, ErrSourceConfig
		}
	}
	a.pending[c.Run.RunID] = arxivPending{state: state, url: entry}
	return fetcher.Request{SourceID: c.Run.SourceID, URL: entry, AllowedHosts: append([]string(nil), c.Config.Config.AllowedHosts...), Format: fetcher.Atom, MinInterval: 3 * time.Second}, nil
}
func arxivID(raw string) (string, string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host != "arxiv.org" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.RawPath != "" || !strings.HasPrefix(u.Path, "/abs/") {
		return "", "", ErrSourceDocument
	}
	full := strings.TrimPrefix(u.Path, "/abs/")
	match := arxivPaper.FindStringSubmatch(full)
	if match == nil {
		return "", "", ErrSourceDocument
	}
	return match[1], full, nil
}
func (a *ArxivAdapter) Parse(c client.RunClaim, response fetcher.Result) (scheduler.Page, error) {
	entry, err := a.entry(c)
	if err != nil {
		return scheduler.Page{}, err
	}
	a.mu.Lock()
	pending, ok := a.pending[c.Run.RunID]
	a.mu.Unlock()
	if !ok || pending.state.Entry != entry || pending.state.Config != c.Run.ConfigVersion || response.StatusCode != 200 || response.FinalURL != pending.url || checkedXML(response.Body) != nil {
		return scheduler.Page{}, ErrSourceDocument
	}
	var feed struct {
		XMLName xml.Name    `xml:"http://www.w3.org/2005/Atom feed"`
		Entries []atomEntry `xml:"http://www.w3.org/2005/Atom entry"`
		Total   *int64      `xml:"http://a9.com/-/spec/opensearch/1.1/ totalResults"`
		Start   *int        `xml:"http://a9.com/-/spec/opensearch/1.1/ startIndex"`
	}
	if xml.Unmarshal(response.Body, &feed) != nil || len(feed.Entries) > 20 || feed.Total != nil && *feed.Total < 0 || feed.Start != nil && *feed.Start != pending.state.Offset {
		return scheduler.Page{}, ErrSourceDocument
	}
	gaps := append([]string(nil), pending.state.Gaps...)
	rows := map[string]scheduler.Item{}
	conflicts := map[string]bool{}
	for _, e := range feed.Entries {
		id, full, idErr := arxivID(e.ID)
		if idErr != nil {
			gaps = hnGap(gaps, "arxiv_identity_invalid")
			continue
		}
		badLink := false
		for _, link := range e.Links {
			if link.Rel == "alternate" || link.Rel == "" {
				_, linkFull, err := arxivID(link.Href)
				if err != nil || linkFull != full {
					badLink = true
				}
			}
		}
		if badLink {
			gaps = hnGap(gaps, "arxiv_link_invalid")
			continue
		}
		if full == id {
			gaps = hnGap(gaps, "arxiv_revision_unspecified")
		}
		published, pubErr := parseSourceTime(e.Published)
		updated, updErr := parseSourceTime(e.Updated)
		if pubErr != nil || published.IsZero() {
			gaps = hnGap(gaps, "entry_published_invalid")
			continue
		}
		if updErr != nil || updated.IsZero() {
			gaps = hnGap(gaps, "entry_updated_invalid")
			continue
		}
		e.ID = id
		e.Links = []atomLink{{Rel: "alternate", Href: "https://arxiv.org/abs/" + full}}
		e.Content = atomText{}
		item, issues, err := a.feed.atomItem(c, e, entry)
		for _, gap := range issues {
			gaps = hnGap(gaps, gap)
		}
		if err != nil || conflicts[id] {
			continue
		}
		if old, ok := rows[id]; ok {
			if bytes.Equal(old.Payload, item.Payload) {
				continue
			}
			if !old.VersionTime.IsZero() && !item.VersionTime.IsZero() && !old.VersionTime.Equal(item.VersionTime) {
				if item.VersionTime.After(old.VersionTime) {
					rows[id] = item
				}
				continue
			}
			delete(rows, id)
			conflicts[id] = true
			gaps = hnGap(gaps, "conflicting_entry_identity")
			continue
		}
		rows[id] = item
	}
	keys := make([]string, 0, len(rows))
	for key := range rows {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	items := make([]scheduler.Item, 0, len(keys))
	for _, key := range keys {
		items = append(items, rows[key])
	}
	offset := pending.state.Offset + len(feed.Entries)
	done := len(feed.Entries) < 20 || feed.Total != nil && int64(offset) >= *feed.Total
	if feed.Total == nil || feed.Start == nil {
		gaps = hnGap(gaps, "arxiv_paging_metadata_missing")
	}
	if offset >= 2000 && !done {
		done = true
		gaps = hnGap(gaps, "arxiv_page_limit")
	}
	state := arxivCursor{Run: c.Run.RunID, Config: c.Run.ConfigVersion, Entry: entry, Offset: offset, Done: done, Gaps: gaps}
	cursor, err := json.Marshal(state)
	if err != nil || len(cursor) > 4096 {
		return scheduler.Page{}, ErrSourceDocument
	}
	if done {
		a.mu.Lock()
		delete(a.pending, c.Run.RunID)
		a.mu.Unlock()
	}
	return scheduler.Page{Items: items, End: done, NextCursor: string(cursor), Gaps: gaps}, nil
}
func (a *ArxivAdapter) Freeze(c client.RunClaim, item scheduler.Item, at time.Time) (outbox.Record, error) {
	return a.feed.Freeze(c, item, at)
}
