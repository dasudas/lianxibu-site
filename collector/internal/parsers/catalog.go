package parsers

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"github.com/yuin/goldmark"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type RepositoryCatalogAdapter struct {
	mu      sync.Mutex
	pending map[string]catalogPending
}
type catalogCursor struct {
	Run          string   `json:"run"`
	Source       string   `json:"source"`
	Config       uint64   `json:"config"`
	Entry        string   `json:"entry"`
	Step         int      `json:"step"`
	RepositoryID int64    `json:"repository_id"`
	Name         string   `json:"name"`
	Description  string   `json:"description"`
	Branch       string   `json:"branch"`
	Tag          string   `json:"tag"`
	Published    string   `json:"published"`
	Commit       string   `json:"commit"`
	Updated      string   `json:"updated"`
	LicensePath  string   `json:"license_path"`
	LicenseHash  string   `json:"license_hash"`
	Gaps         []string `json:"gaps"`
}
type catalogPending struct {
	state catalogCursor
	url   string
}

var _ scheduler.Adapter = (*RepositoryCatalogAdapter)(nil)
var _ scheduler.CompletedCursor = (*RepositoryCatalogAdapter)(nil)
var _ scheduler.NotFoundAdapter = (*RepositoryCatalogAdapter)(nil)

func (a *RepositoryCatalogAdapter) AllowNotFound() bool { return true }
func Agents() *RepositoryCatalogAdapter {
	return &RepositoryCatalogAdapter{pending: map[string]catalogPending{}}
}
func agentEntry(c client.RunClaim) (string, string, error) {
	if c.Run.RunID == "" || c.Run.SourceID == "" || c.Config.SourceID != c.Run.SourceID || c.Run.ConfigVersion != c.Config.ConfigVersion || c.Config.Config.ParserVersion != "github-agent-v1" || len(c.Config.Config.EntryURLs) != 1 {
		return "", "", ErrSourceConfig
	}
	entry := c.Config.Config.EntryURLs[0]
	checked, err := sourceURL(entry, "", c.Config.Config.AllowedHosts)
	if err != nil {
		return "", "", err
	}
	u, _ := url.Parse(checked)
	if u.Host != "api.github.com" || u.RawPath != "" || u.RawQuery != "" || u.Fragment != "" {
		return "", "", ErrSourceConfig
	}
	repo := strings.TrimPrefix(u.Path, "/repos/")
	if u.Path != "/repos/"+repo {
		return "", "", ErrSourceConfig
	}
	switch repo {
	case "openai/codex", "anthropics/claude-code", "google-gemini/gemini-cli":
	default:
		return "", "", ErrSourceConfig
	}
	return entry, repo, nil
}
func (a *RepositoryCatalogAdapter) state(c client.RunClaim, cursor string) (catalogCursor, error) {
	if a == nil {
		return catalogCursor{}, ErrSourceConfig
	}
	entry, _, err := agentEntry(c)
	if err != nil {
		return catalogCursor{}, err
	}
	if cursor == "" {
		return catalogCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: c.Run.ConfigVersion, Entry: entry, Gaps: []string{"missing_author_report"}}, nil
	}
	var state catalogCursor
	d := json.NewDecoder(strings.NewReader(cursor))
	d.DisallowUnknownFields()
	if len(cursor) > 16384 || checkedJSON([]byte(cursor)) != nil || d.Decode(&state) != nil || state.Run != c.Run.RunID || state.Source != c.Run.SourceID || state.Config != c.Run.ConfigVersion || state.Entry != entry || state.Step < 1 || state.Step > 5 || state.RepositoryID <= 0 || state.RepositoryID > 9007199254740991 || !repoPart(state.Branch) || len(state.Name) > 2048 || len(state.Description) > 8192 || len(state.Tag) > 100 || state.Tag != "" && !repoPart(state.Tag) || len(state.Gaps) > 32 {
		return catalogCursor{}, ErrSourceConfig
	}
	if state.Published != "" {
		if _, e := parseSourceTime(state.Published); e != nil {
			return catalogCursor{}, ErrSourceConfig
		}
	}
	if state.Step >= 3 && (!gitCommit(state.Commit) || state.Updated == "") {
		return catalogCursor{}, ErrSourceConfig
	}
	if state.Updated != "" {
		if _, e := parseSourceTime(state.Updated); e != nil {
			return catalogCursor{}, ErrSourceConfig
		}
	}
	if state.LicensePath != "" && (!catalogPath(state.LicensePath) || len(state.LicenseHash) != 64) {
		return catalogCursor{}, ErrSourceConfig
	}
	for _, gap := range state.Gaps {
		switch gap {
		case "missing_author_report", "missing_license", "no_formal_release", "release_scan_limit", "missing_readme", "document_truncated", "summary_truncated":
		default:
			return catalogCursor{}, ErrSourceConfig
		}
	}
	return state, nil
}
func gitCommit(value string) bool {
	if len(value) != 40 {
		return false
	}
	for _, r := range value {
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f') {
			return false
		}
	}
	return true
}
func catalogPath(value string) bool {
	if value == "" || len(value) > 1024 {
		return false
	}
	for _, part := range strings.Split(value, "/") {
		if !repoPart(part) {
			return false
		}
	}
	return true
}
func (a *RepositoryCatalogAdapter) Completed(c client.RunClaim, cursor string) (bool, []string, error) {
	state, err := a.state(c, cursor)
	if err != nil {
		return false, nil, err
	}
	return state.Step == 5, append([]string(nil), state.Gaps...), nil
}
func (a *RepositoryCatalogAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	state, err := a.state(c, cursor)
	if err != nil || state.Step == 5 {
		return fetcher.Request{}, ErrSourceConfig
	}
	target := state.Entry
	switch state.Step {
	case 1:
		target += "/releases?per_page=30&page=1"
	case 2:
		ref := state.Tag
		if ref == "" {
			ref = state.Branch
		}
		target += "/commits/" + url.PathEscape(ref)
	case 3:
		target += "/license?ref=" + state.Commit
	case 4:
		target += "/readme?ref=" + state.Commit
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.pending) >= 100 {
		if _, ok := a.pending[c.Run.RunID]; !ok {
			return fetcher.Request{}, ErrSourceConfig
		}
	}
	a.pending[c.Run.RunID] = catalogPending{state: state, url: target}
	return fetcher.Request{SourceID: c.Run.SourceID, URL: target, AllowedHosts: append([]string(nil), c.Config.Config.AllowedHosts...), Format: fetcher.JSON, MinInterval: time.Second}, nil
}
func catalogFile(raw []byte) (string, []byte, string, error) {
	var value struct {
		Type     string `json:"type"`
		Path     string `json:"path"`
		Encoding string `json:"encoding"`
		Content  string `json:"content"`
		License  struct {
			SPDX string `json:"spdx_id"`
		} `json:"license"`
	}
	if json.Unmarshal(raw, &value) != nil || value.Type != "file" || !catalogPath(value.Path) || value.Encoding != "base64" {
		return "", nil, "", ErrSourceDocument
	}
	data, err := base64.StdEncoding.DecodeString(value.Content)
	if err != nil || len(data) > 200*1024 || !utf8.Valid(data) {
		return "", nil, "", ErrSourceDocument
	}
	return value.Path, data, value.License.SPDX, nil
}
func (a *RepositoryCatalogAdapter) Parse(c client.RunClaim, response fetcher.Result) (scheduler.Page, error) {
	entry, repo, err := agentEntry(c)
	if err != nil || a == nil {
		return scheduler.Page{}, ErrSourceConfig
	}
	a.mu.Lock()
	pending, ok := a.pending[c.Run.RunID]
	a.mu.Unlock()
	if !ok || pending.state.Entry != entry || pending.state.Config != c.Run.ConfigVersion || response.FinalURL != pending.url || checkedJSON(response.Body) != nil {
		return scheduler.Page{}, ErrSourceDocument
	}
	state := pending.state
	items := []scheduler.Item{}
	if response.StatusCode != 200 && !(response.StatusCode == 404 && (state.Step == 1 || state.Step == 3 || state.Step == 4)) {
		return scheduler.Page{}, ErrSourceDocument
	}
	switch state.Step {
	case 0:
		var value struct {
			ID          int64   `json:"id"`
			FullName    string  `json:"full_name"`
			Name        string  `json:"name"`
			Description *string `json:"description"`
			Branch      string  `json:"default_branch"`
		}
		if json.Unmarshal(response.Body, &value) != nil || value.ID <= 0 || value.ID > 9007199254740991 || value.FullName != repo || !repoPart(value.Branch) {
			return scheduler.Page{}, ErrSourceDocument
		}
		state.RepositoryID = value.ID
		state.Branch = value.Branch
		state.Name = value.Name
		if state.Name == "" {
			state.Name = repo
		}
		state.Name, _ = boundText(state.Name, 512, 2048)
		if value.Description != nil {
			var truncated bool
			state.Description, truncated = boundText(*value.Description, 8192, 8192)
			if truncated {
				state.Gaps = hnGap(state.Gaps, "summary_truncated")
			}
		}
	case 1:
		if response.StatusCode == 200 {
			var releases []struct {
				Draft      *bool  `json:"draft"`
				Prerelease *bool  `json:"prerelease"`
				Tag        string `json:"tag_name"`
				Published  string `json:"published_at"`
			}
			if json.Unmarshal(response.Body, &releases) != nil || releases == nil || len(releases) > 30 {
				return scheduler.Page{}, ErrSourceDocument
			}
			if len(releases) == 30 {
				state.Gaps = hnGap(state.Gaps, "release_scan_limit")
			}
			for _, release := range releases {
				if release.Draft == nil || release.Prerelease == nil {
					return scheduler.Page{}, ErrSourceDocument
				}
				if *release.Draft || *release.Prerelease {
					continue
				}
				at, e := parseSourceTime(release.Published)
				if !repoPart(release.Tag) || e != nil || at.IsZero() {
					return scheduler.Page{}, ErrSourceDocument
				}
				state.Tag = release.Tag
				state.Published = wireTime(at)
				break
			}
		}
		if state.Tag == "" {
			state.Gaps = hnGap(state.Gaps, "no_formal_release")
		}
	case 2:
		var value struct {
			SHA    string `json:"sha"`
			Commit struct {
				Committer struct {
					Date string `json:"date"`
				} `json:"committer"`
			} `json:"commit"`
		}
		if json.Unmarshal(response.Body, &value) != nil || !gitCommit(value.SHA) {
			return scheduler.Page{}, ErrSourceDocument
		}
		at, e := parseSourceTime(value.Commit.Committer.Date)
		if e != nil || at.IsZero() {
			return scheduler.Page{}, ErrSourceDocument
		}
		state.Commit = value.SHA
		state.Updated = wireTime(at)
	case 3:
		if response.StatusCode == 404 {
			state.Gaps = hnGap(state.Gaps, "missing_license")
		} else {
			path, data, spdx, e := catalogFile(response.Body)
			if e != nil {
				return scheduler.Page{}, e
			}
			sum := sha256.Sum256(data)
			state.LicensePath = path
			state.LicenseHash = hex.EncodeToString(sum[:])
			if spdx == "" || spdx == "NOASSERTION" {
				state.Gaps = hnGap(state.Gaps, "missing_license")
			}
		}
	case 4:
		var document any
		var fileEvidence map[string]any
		truncated := false
		if response.StatusCode == 404 {
			state.Gaps = hnGap(state.Gaps, "missing_readme")
		} else {
			path, data, _, e := catalogFile(response.Body)
			if e != nil {
				return scheduler.Page{}, e
			}
			if !strings.HasSuffix(strings.ToLower(path), ".md") {
				return scheduler.Page{}, ErrSourceDocument
			}
			sum := sha256.Sum256(data)
			var rendered bytes.Buffer
			if goldmark.Convert(data, &rendered) != nil {
				return scheduler.Page{}, ErrSourceDocument
			}
			text := strings.Join(strings.Fields(htmlText(rendered.String())), " ")
			document, truncated = boundText(text, 120000, 120*1024)
			if truncated {
				state.Gaps = hnGap(state.Gaps, "document_truncated")
			}
			fileEvidence = catalogEvidence("https://github.com/"+repo+"/blob/"+state.Commit+"/"+path, state.Commit, path, hex.EncodeToString(sum[:]), "readme")
		}
		evidence := []any{catalogEvidence("https://github.com/"+repo, state.Commit, nil, nil, "metadata")}
		if state.Tag != "" {
			evidence = append(evidence, catalogEvidence("https://github.com/"+repo+"/releases/tag/"+url.PathEscape(state.Tag), state.Tag, nil, nil, "release"))
		}
		if state.LicensePath != "" {
			evidence = append(evidence, catalogEvidence("https://github.com/"+repo+"/blob/"+state.Commit+"/"+state.LicensePath, state.Commit, state.LicensePath, state.LicenseHash, "metadata"))
		}
		if fileEvidence != nil {
			evidence = append(evidence, fileEvidence)
		}
		payload := map[string]any{"schema_version": "1", "parser_version": "github-agent-v1", "config_version": json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)), "category": "agent", "identity": map[string]any{"namespace": "github_repository", "key": strconv.FormatInt(state.RepositoryID, 10)}, "title": state.Name, "source_url": "https://github.com/" + repo, "version": catalogNullable(state.Tag), "published_at": catalogNullable(state.Published), "updated_at": state.Updated, "summary": catalogNullable(state.Description), "document_text": document, "truncated": truncated, "evidence": evidence}
		if err := client.ValidateCatalogPayload(payload); err != nil {
			return scheduler.Page{}, err
		}
		raw, _ := json.Marshal(payload)
		at, _ := parseSourceTime(state.Updated)
		items = append(items, scheduler.Item{Kind: "catalog", SourceItemID: strconv.FormatInt(state.RepositoryID, 10), EvidenceTime: at, Version: state.Commit, VersionTime: at, EvidenceRef: "https://github.com/" + repo + "/tree/" + state.Commit, Payload: raw})
	default:
		return scheduler.Page{}, ErrSourceConfig
	}
	state.Step++
	cursor, e := json.Marshal(state)
	if e != nil || len(cursor) > 16384 {
		return scheduler.Page{}, ErrSourceDocument
	}
	if state.Step == 5 {
		a.mu.Lock()
		delete(a.pending, c.Run.RunID)
		a.mu.Unlock()
	}
	return scheduler.Page{Items: items, NextCursor: string(cursor), End: state.Step == 5, Gaps: append([]string(nil), state.Gaps...)}, nil
}
func catalogNullable(value string) any {
	if value == "" {
		return nil
	}
	return value
}
func catalogEvidence(link string, revision, path, hash any, kind string) map[string]any {
	return map[string]any{"url": link, "revision": revision, "path": path, "file_hash": hash, "evidence_type": kind}
}
func (a *RepositoryCatalogAdapter) Freeze(c client.RunClaim, item scheduler.Item, at time.Time) (outbox.Record, error) {
	_, repo, configErr := agentEntry(c)
	if configErr != nil {
		return outbox.Record{}, configErr
	}
	if item.Kind != "catalog" || item.SourceItemID == "" || at.IsZero() {
		return outbox.Record{}, ErrSourceDocument
	}
	payload, err := client.ParseStrict(item.Payload)
	if err != nil {
		return outbox.Record{}, err
	}
	value, ok := payload.(map[string]any)
	if !ok || client.ValidateCatalogPayload(value) != nil {
		return outbox.Record{}, ErrSourceDocument
	}
	identity := value["identity"].(map[string]any)
	if value["category"] != "agent" || value["parser_version"] != "github-agent-v1" || value["config_version"] != json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)) || identity["key"] != item.SourceItemID || value["source_url"] != "https://github.com/"+repo {
		return outbox.Record{}, ErrSourceConfig
	}
	var random [16]byte
	if _, err = rand.Read(random[:]); err != nil {
		return outbox.Record{}, err
	}
	id := hex.EncodeToString(random[:])
	member, err := client.Hash(client.SourceItemHash, map[string]any{"source_id": c.Run.SourceID, "source_item_id": item.SourceItemID})
	if err != nil {
		return outbox.Record{}, err
	}
	row := map[string]any{"protocol_revision": "0.11", "observation_id": id, "member_id": member, "run_id": c.Run.RunID, "business_date": c.Run.BusinessDate, "source_id": c.Run.SourceID, "source_revision": c.Run.ConfigVersion, "source_item_id": item.SourceItemID, "kind": "catalog", "retrieved_at": wireTime(at), "replaces_observation_id": nil, "payload": value}
	hash, err := client.Hash(client.ObservationHash, row)
	if err != nil {
		return outbox.Record{}, err
	}
	row["payload_hash"] = hash
	wire, err := json.Marshal(row)
	if err != nil {
		return outbox.Record{}, err
	}
	if _, err = client.DecodeObservation(wire); err != nil {
		return outbox.Record{}, err
	}
	key, err := client.Hash(client.SourceItemHash, map[string]any{"source_id": c.Run.SourceID, "source_item_id": id})
	if err != nil {
		return outbox.Record{}, err
	}
	return outbox.Record{ID: id, Kind: "observation", RecordKey: key, SourceID: c.Run.SourceID, RunID: c.Run.RunID, MemberID: member, ObservationID: id, Payload: wire, PayloadHash: hash, ProtocolRevision: "0.11", HashVersion: 1}, nil
}
