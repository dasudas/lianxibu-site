package parsers

import (
	"bytes"
	"encoding/json"
	"encoding/xml"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
)

type rssItem struct {
	Title       string `xml:"title"`
	Link        string `xml:"link"`
	Published   string `xml:"pubDate"`
	Description string `xml:"description"`
}
type rssFeed struct {
	XMLName  xml.Name `xml:"rss"`
	Channels []struct {
		Items []rssItem  `xml:"item"`
		Links []atomLink `xml:"http://www.w3.org/2005/Atom link"`
	} `xml:"channel"`
}

func canonicalLink(raw, base string) (string, error) {
	checked, err := articleURL(strings.TrimSpace(raw), base)
	if err != nil {
		return "", err
	}
	u, _ := url.Parse(checked)
	u.Host = strings.ToLower(u.Host)
	if u.Port() == "443" {
		u.Host = strings.TrimSuffix(u.Host, ":443")
	}
	u.RawQuery = u.Query().Encode()
	return u.String(), nil
}
func rssTime(raw string) (time.Time, error) {
	for _, layout := range []string{time.RFC1123Z, time.RFC1123, time.RFC822Z, time.RFC822, time.RFC3339Nano} {
		if (layout == time.RFC1123 || layout == time.RFC822) && !strings.HasSuffix(raw, " GMT") && !strings.HasSuffix(raw, " UTC") {
			continue
		}
		if at, err := time.Parse(layout, strings.TrimSpace(raw)); err == nil {
			return at.UTC(), nil
		}
	}
	return time.Time{}, ErrSourceDocument
}
func (a *FeedAdapter) parseRSS(c client.RunClaim, response fetcher.Result) (scheduler.Page, error) {
	var feed rssFeed
	if xml.Unmarshal(response.Body, &feed) != nil || feed.XMLName.Space != "" || len(feed.Channels) != 1 || len(feed.Channels[0].Items) > 1000 {
		return scheduler.Page{}, ErrSourceDocument
	}
	base := response.FinalURL
	if base == "" {
		base = c.Config.Config.EntryURLs[0]
	}
	if _, err := sourceURL(base, "", c.Config.Config.AllowedHosts); err != nil {
		return scheduler.Page{}, err
	}
	page := scheduler.Page{End: true, Items: []scheduler.Item{}, Gaps: []string{"feed_coverage_unproven"}}
	for _, link := range feed.Channels[0].Links {
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
	rows := map[string]scheduler.Item{}
	conflicts := map[string]bool{}
	for _, row := range feed.Channels[0].Items {
		link, err := canonicalLink(row.Link, base)
		if err != nil {
			page.Gaps = append(page.Gaps, "entry_link_invalid")
			continue
		}
		at, err := rssTime(row.Published)
		if err != nil {
			page.Gaps = append(page.Gaps, "entry_time_unknown")
			continue
		}
		entry := atomEntry{ID: link, Title: atomText{Type: "html", Text: row.Title}, Published: at.Format(time.RFC3339Nano), Links: []atomLink{{Rel: "alternate", Href: link}}, Summary: atomText{Type: "html", Text: row.Description}}
		item, gaps, err := a.atomItem(c, entry, base)
		page.Gaps = append(page.Gaps, gaps...)
		if err != nil {
			continue
		}
		var article Article
		json.Unmarshal(item.Payload, &article)
		article.RawPublished = row.Published
		raw, truncated := boundText(row.Description, 200000, 200*1024)
		article.RawSelectedContent = raw
		article.BodyTruncated = article.BodyTruncated || truncated
		if a.onlyDevelopment {
			if article.BodyTruncated {
				page.Gaps = append(page.Gaps, "topic_summary_truncated")
			}
			summary := ""
			if article.SourceSummary != nil {
				summary = *article.SourceSummary
			}
			if !developmentTopic(article.Title, summary) {
				continue
			}
		}
		item.Payload, _ = json.Marshal(article)
		if conflicts[link] {
			continue
		}
		if old, exists := rows[link]; exists {
			if !bytes.Equal(old.Payload, item.Payload) {
				delete(rows, link)
				conflicts[link] = true
				page.Gaps = append(page.Gaps, "conflicting_entry_identity")
			}
			continue
		}
		rows[link] = item
	}
	keys := make([]string, 0, len(rows))
	for key := range rows {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		page.Items = append(page.Items, rows[key])
	}
	return page, nil
}
