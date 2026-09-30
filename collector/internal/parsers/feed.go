// Package parsers normalizes bounded source documents without executing source content.
package parsers

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"io"
	"net/url"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"golang.org/x/net/html"
)

const atomNS = "http://www.w3.org/2005/Atom"

var ErrSourceDocument = errors.New("source document cannot be normalized")
var ErrSourceConfig = errors.New("source parser configuration mismatch")

// Article is a local normalization result. Freeze emits only the public wire whitelist.
type Article struct {
	Title              string
	SourceURL          string
	PublishedAt        *string
	UpdatedAt          *string
	TimeBasis          string
	SourceSummary      *string
	BodyText           *string
	ContentStatus      string
	BodyTruncated      bool
	RawPublished       string
	RawUpdated         string
	RawSelectedContent string
	Authors            []string
	Tags               []string
}
type FeedAdapter struct {
	sourceID, version string
	format            fetcher.Format
}

func Ruanyifeng() *FeedAdapter {
	return &FeedAdapter{sourceID: "cn-ruanyifeng", version: "cn-ruanyifeng-v1", format: fetcher.Atom}
}
func Solidot() *FeedAdapter {
	return &FeedAdapter{sourceID: "cn-solidot", version: "cn-solidot-v1", format: fetcher.RSS}
}
func Cnblogs() *FeedAdapter {
	return &FeedAdapter{sourceID: "cn-cnblogs", version: "cn-cnblogs-v1", format: fetcher.Atom}
}

var _ scheduler.Adapter = (*FeedAdapter)(nil)

func (a *FeedAdapter) valid(c client.RunClaim) bool {
	return a != nil && c.Run.SourceID == a.sourceID && c.Config.SourceID == c.Run.SourceID && c.Config.ConfigVersion == c.Run.ConfigVersion && c.Config.Config.ParserVersion == a.version && len(c.Config.Config.EntryURLs) > 0 && len(c.Config.Config.AllowedHosts) > 0
}
func (a *FeedAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	if !a.valid(c) {
		return fetcher.Request{}, ErrSourceConfig
	}
	target := c.Config.Config.EntryURLs[0]
	if cursor != "" {
		target = cursor
	}
	checked, err := sourceURL(target, "", c.Config.Config.AllowedHosts)
	if err != nil {
		return fetcher.Request{}, err
	}
	return fetcher.Request{SourceID: c.Run.SourceID, URL: checked, AllowedHosts: append([]string(nil), c.Config.Config.AllowedHosts...), Format: a.format, MinInterval: time.Second}, nil
}

type atomText struct {
	Type  string `xml:"type,attr"`
	Src   string `xml:"src,attr"`
	Text  string `xml:",chardata"`
	Inner string `xml:",innerxml"`
}
type atomLink struct {
	Rel  string `xml:"rel,attr"`
	Href string `xml:"href,attr"`
	Type string `xml:"type,attr"`
}
type atomEntry struct {
	ID        string     `xml:"http://www.w3.org/2005/Atom id"`
	Title     atomText   `xml:"http://www.w3.org/2005/Atom title"`
	Published string     `xml:"http://www.w3.org/2005/Atom published"`
	Updated   string     `xml:"http://www.w3.org/2005/Atom updated"`
	Links     []atomLink `xml:"http://www.w3.org/2005/Atom link"`
	Content   atomText   `xml:"http://www.w3.org/2005/Atom content"`
	Summary   atomText   `xml:"http://www.w3.org/2005/Atom summary"`
	Authors   []struct {
		Name string `xml:"http://www.w3.org/2005/Atom name"`
	} `xml:"http://www.w3.org/2005/Atom author"`
	Categories []struct {
		Term string `xml:"term,attr"`
	} `xml:"http://www.w3.org/2005/Atom category"`
}
type atomFeed struct {
	XMLName xml.Name    `xml:"http://www.w3.org/2005/Atom feed"`
	Links   []atomLink  `xml:"http://www.w3.org/2005/Atom link"`
	Entries []atomEntry `xml:"http://www.w3.org/2005/Atom entry"`
}

func checkedXML(raw []byte) error {
	if len(raw) == 0 || len(raw) > 2*1024*1024 || !utf8.Valid(raw) {
		return ErrSourceDocument
	}
	d := xml.NewDecoder(bytes.NewReader(raw))
	depth, roots := 0, 0
	for {
		token, err := d.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return ErrSourceDocument
		}
		switch value := token.(type) {
		case xml.Directive:
			return ErrSourceDocument
		case xml.ProcInst:
			if value.Target != "xml" {
				return ErrSourceDocument
			}
		case xml.StartElement:
			if depth == 0 {
				roots++
			}
			depth++
			if depth > 64 {
				return ErrSourceDocument
			}
		case xml.EndElement:
			depth--
		case xml.CharData:
			if depth == 0 && strings.TrimSpace(string(value)) != "" {
				return ErrSourceDocument
			}
		}
	}
	if roots != 1 || depth != 0 {
		return ErrSourceDocument
	}
	return nil
}
func (a *FeedAdapter) Parse(c client.RunClaim, response fetcher.Result) (scheduler.Page, error) {
	if !a.valid(c) {
		return scheduler.Page{}, ErrSourceConfig
	}
	if response.StatusCode != 200 || checkedXML(response.Body) != nil {
		return scheduler.Page{}, ErrSourceDocument
	}
	if a.format == fetcher.RSS {
		return a.parseRSS(c, response)
	}
	var feed atomFeed
	if xml.Unmarshal(response.Body, &feed) != nil || len(feed.Entries) > 1000 {
		return scheduler.Page{}, ErrSourceDocument
	}
	page := scheduler.Page{End: true, Items: []scheduler.Item{}, Gaps: []string{"feed_coverage_unproven"}}
	base := response.FinalURL
	if base == "" {
		base = c.Config.Config.EntryURLs[0]
	}
	if _, err := sourceURL(base, "", c.Config.Config.AllowedHosts); err != nil {
		return page, err
	}
	for _, link := range feed.Links {
		if link.Rel == "next" {
			next, err := sourceURL(link.Href, base, c.Config.Config.AllowedHosts)
			if err != nil || next == base || page.NextCursor != "" {
				page.Gaps = append(page.Gaps, "unsafe_or_ambiguous_next")
				continue
			}
			page.End = false
			page.NextCursor = next
		}
	}
	byID := map[string]scheduler.Item{}
	conflicts := map[string]bool{}
	for _, entry := range feed.Entries {
		item, gaps, err := a.atomItem(c, entry, base)
		page.Gaps = append(page.Gaps, gaps...)
		if err != nil {
			continue
		}
		if conflicts[item.SourceItemID] {
			continue
		}
		old, exists := byID[item.SourceItemID]
		if !exists {
			byID[item.SourceItemID] = item
			continue
		}
		if bytes.Equal(old.Payload, item.Payload) {
			continue
		}
		if !item.VersionTime.IsZero() && !old.VersionTime.IsZero() && !item.VersionTime.Equal(old.VersionTime) {
			if item.VersionTime.After(old.VersionTime) {
				byID[item.SourceItemID] = item
			}
			continue
		}
		delete(byID, item.SourceItemID)
		conflicts[item.SourceItemID] = true
		page.Gaps = append(page.Gaps, "conflicting_entry_identity")
	}
	keys := make([]string, 0, len(byID))
	for key := range byID {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		page.Items = append(page.Items, byID[key])
	}
	return page, nil
}
func (a *FeedAdapter) atomItem(c client.RunClaim, e atomEntry, base string) (scheduler.Item, []string, error) {
	fail := func(code string) (scheduler.Item, []string, error) {
		return scheduler.Item{}, []string{code}, ErrSourceDocument
	}
	id := strings.TrimSpace(e.ID)
	if id == "" || len(id) > 4096 || strings.IndexFunc(id, unicode.IsControl) >= 0 {
		return fail("entry_identity_missing")
	}
	title, _, err := construct(e.Title, 512, 4096)
	if err != nil || strings.TrimSpace(title) == "" {
		return fail("entry_title_invalid")
	}
	link := ""
	for _, candidate := range e.Links {
		if candidate.Rel == "alternate" || candidate.Rel == "" {
			checked, err := articleURL(candidate.Href, base)
			if err == nil {
				link = checked
				break
			}
		}
	}
	if link == "" {
		return fail("entry_link_invalid")
	}
	article := Article{Title: title, SourceURL: link, RawPublished: e.Published, RawUpdated: e.Updated, ContentStatus: "unavailable", TimeBasis: "unknown"}
	gaps := []string{}
	published, pubErr := parseSourceTime(e.Published)
	updated, updErr := parseSourceTime(e.Updated)
	if pubErr != nil {
		gaps = append(gaps, "entry_published_invalid")
	}
	if updErr != nil {
		gaps = append(gaps, "entry_updated_invalid")
	}
	if !published.IsZero() {
		value := wireTime(published)
		article.PublishedAt = &value
		article.TimeBasis = "published"
	}
	if !updated.IsZero() {
		value := wireTime(updated)
		article.UpdatedAt = &value
		article.TimeBasis = "updated"
	}
	when := updated
	if when.IsZero() {
		when = published
	}
	if when.IsZero() {
		return fail("entry_time_unknown")
	}
	if !published.IsZero() && !updated.IsZero() && updated.Before(published) {
		return fail("entry_time_inconsistent")
	}
	for _, author := range e.Authors {
		if name := strings.TrimSpace(author.Name); name != "" {
			article.Authors = append(article.Authors, name)
		}
	}
	for _, category := range e.Categories {
		article.Tags = append(article.Tags, category.Term)
	}
	if e.Summary.Text != "" || e.Summary.Inner != "" {
		text, truncated, err := construct(e.Summary, 16384, 64*1024)
		if err != nil {
			gaps = append(gaps, "entry_summary_unsupported")
		} else {
			article.SourceSummary = &text
			article.BodyTruncated = truncated
		}
	}
	if e.Content.Src != "" {
		gaps = append(gaps, "external_content_unfetched")
	} else if e.Content.Text != "" || e.Content.Inner != "" {
		raw := e.Content.Text
		if e.Content.Type == "xhtml" {
			raw = e.Content.Inner
		}
		rawBound, truncatedRaw := boundText(raw, 200000, 200*1024)
		article.RawSelectedContent = rawBound
		article.BodyTruncated = article.BodyTruncated || truncatedRaw
		text, truncated, err := construct(e.Content, 50000, 200*1024)
		if err != nil {
			gaps = append(gaps, "entry_content_unsupported")
		} else {
			article.BodyText = &text
			article.ContentStatus = "full"
			article.BodyTruncated = article.BodyTruncated || truncated
			if article.BodyTruncated {
				article.ContentStatus = "partial"
			}
		}
	}
	payload, err := json.Marshal(article)
	if err != nil {
		return fail("entry_normalization_failed")
	}
	version := wireTime(updated)
	if updated.IsZero() {
		digest := sha256.Sum256(payload)
		version = hex.EncodeToString(digest[:])
	}
	return scheduler.Item{SourceItemID: id, EvidenceTime: when, Version: version, VersionTime: updated, EvidenceRef: link, Payload: payload}, gaps, nil
}

func construct(value atomText, maxRunes, maxBytes int) (string, bool, error) {
	var text string
	switch value.Type {
	case "", "text":
		text = value.Text
	case "html":
		text = htmlText(value.Text)
	case "xhtml":
		text = htmlText(value.Inner)
	default:
		return "", false, ErrSourceDocument
	}
	text = strings.Join(strings.Fields(text), " ")
	returnText, truncated := boundText(text, maxRunes, maxBytes)
	return returnText, truncated, nil
}
func htmlText(raw string) string {
	root, err := html.Parse(strings.NewReader(raw))
	if err != nil {
		return ""
	}
	var text strings.Builder
	var walk func(*html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode {
			switch n.Data {
			case "script", "style", "iframe", "object", "template", "noscript", "svg":
				return
			}
		}
		if n.Type == html.TextNode {
			text.WriteString(n.Data)
			text.WriteByte(' ')
		}
		for child := n.FirstChild; child != nil; child = child.NextSibling {
			walk(child)
		}
	}
	walk(root)
	return text.String()
}
func boundText(text string, maxRunes, maxBytes int) (string, bool) {
	count := 0
	for index, scalar := range text {
		if count == maxRunes || index+utf8.RuneLen(scalar) > maxBytes {
			return text[:index], true
		}
		count++
	}
	return text, false
}
func parseSourceTime(raw string) (time.Time, error) {
	if strings.TrimSpace(raw) == "" {
		return time.Time{}, nil
	}
	value, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(raw))
	if err != nil {
		return time.Time{}, ErrSourceDocument
	}
	return value.UTC().Truncate(time.Microsecond), nil
}
func wireTime(at time.Time) string { return at.UTC().Format("2006-01-02T15:04:05.000000Z") }
func articleURL(raw, base string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil || raw == "" {
		return "", ErrSourceDocument
	}
	if base != "" {
		b, err := url.Parse(base)
		if err != nil {
			return "", ErrSourceDocument
		}
		u = b.ResolveReference(u)
	}
	if u.Scheme != "https" || u.Hostname() == "" || u.User != nil || len(u.String()) > 2000 {
		return "", ErrSourceDocument
	}
	u.Fragment = ""
	return u.String(), nil
}
func sourceURL(raw, base string, allowed []string) (string, error) {
	value, err := articleURL(raw, base)
	if err != nil {
		return "", ErrSourceConfig
	}
	u, _ := url.Parse(value)
	if u.Port() != "" && u.Port() != "443" {
		return "", ErrSourceConfig
	}
	for _, host := range allowed {
		if strings.EqualFold(u.Hostname(), host) {
			return value, nil
		}
	}
	return "", ErrSourceConfig
}

func (a *FeedAdapter) Freeze(c client.RunClaim, item scheduler.Item, at time.Time) (outbox.Record, error) {
	if !a.valid(c) || item.SourceItemID == "" || item.EvidenceTime.IsZero() || at.IsZero() {
		return outbox.Record{}, ErrSourceConfig
	}
	var article Article
	if json.Unmarshal(item.Payload, &article) != nil {
		return outbox.Record{}, ErrSourceDocument
	}
	member, err := client.Hash(client.SourceItemHash, map[string]any{"source_id": c.Run.SourceID, "source_item_id": item.SourceItemID})
	if err != nil {
		return outbox.Record{}, err
	}
	var random [16]byte
	if _, err := rand.Read(random[:]); err != nil {
		return outbox.Record{}, err
	}
	id := hex.EncodeToString(random[:])
	order := map[string]any{"basis": "unknown", "domain": nil, "value": nil, "evidence_ref": nil}
	if article.UpdatedAt != nil {
		order = map[string]any{"basis": "revision_time", "domain": c.Run.SourceID, "value": *article.UpdatedAt, "evidence_ref": article.SourceURL}
	}
	payload := map[string]any{"title": article.Title, "source_url": article.SourceURL, "published_at": article.PublishedAt, "updated_at": article.UpdatedAt, "time_basis": article.TimeBasis, "source_summary": article.SourceSummary, "body_text": article.BodyText, "content_status": article.ContentStatus, "body_truncated": article.BodyTruncated, "parser_version": a.version, "config_version": c.Run.ConfigVersion, "source_order": order}
	// Canonical values use nil/string, never Go pointer types.
	for key, value := range map[string]*string{"published_at": article.PublishedAt, "updated_at": article.UpdatedAt, "source_summary": article.SourceSummary, "body_text": article.BodyText} {
		if value == nil {
			payload[key] = nil
		} else {
			payload[key] = *value
		}
	}
	row := map[string]any{"protocol_revision": "0.11", "observation_id": id, "member_id": member, "run_id": c.Run.RunID, "business_date": c.Run.BusinessDate, "source_id": c.Run.SourceID, "source_revision": c.Run.ConfigVersion, "source_item_id": item.SourceItemID, "kind": "article", "retrieved_at": wireTime(at), "replaces_observation_id": nil, "payload": payload}
	hash, err := client.Hash(client.ObservationHash, row)
	if err != nil {
		return outbox.Record{}, err
	}
	row["payload_hash"] = hash
	wire, err := json.Marshal(row)
	if err != nil {
		return outbox.Record{}, err
	}
	if _, err := client.DecodeObservation(wire); err != nil {
		return outbox.Record{}, err
	}
	key, err := client.Hash(client.SourceItemHash, map[string]any{"source_id": c.Run.SourceID, "source_item_id": id})
	if err != nil {
		return outbox.Record{}, err
	}
	return outbox.Record{ID: id, Kind: "observation", RecordKey: key, SourceID: c.Run.SourceID, RunID: c.Run.RunID, MemberID: member, ObservationID: id, Payload: wire, PayloadHash: hash, ProtocolRevision: "0.11", HashVersion: 1}, nil
}
