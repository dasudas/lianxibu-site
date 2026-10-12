package parsers

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"github.com/yuin/goldmark"
	"gopkg.in/yaml.v3"
	"io"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

type SkillAdapter struct {
	mu      sync.Mutex
	pending map[string]catalogPending
}

var _ scheduler.Adapter = (*SkillAdapter)(nil)
var _ scheduler.CompletedCursor = (*SkillAdapter)(nil)
var _ scheduler.NotFoundAdapter = (*SkillAdapter)(nil)

func (a *SkillAdapter) AllowNotFound() bool { return true }
func Skills() *SkillAdapter                 { return &SkillAdapter{pending: map[string]catalogPending{}} }

func skillLicensePath(repo, path string) string {
	if repo == "vercel-labs/agent-skills" {
		return path
	}
	return strings.TrimSuffix(path, "SKILL.md") + "LICENSE.txt"
}

// This recognizes a declaration, not permission to publish. Admission separately
// binds the exact file hash and reviewed usage scope.
func skillLicenseDeclaration(repo string, data []byte) string {
	if repo == "vercel-labs/agent-skills" {
		if _, _, _, err := skillMetadata(data); err != nil {
			return ""
		}
		text := strings.ReplaceAll(string(data), "\r\n", "\n")
		end := strings.Index(text[4:], "\n---\n") + 4
		var values map[string]any
		if yaml.Unmarshal([]byte(text[4:end]), &values) == nil && values["license"] == "MIT" {
			return "MIT"
		}
		return ""
	}
	text := strings.Join(strings.Fields(string(data)), " ")
	if strings.Contains(text, "Apache License Version 2.0, January 2004") && strings.Contains(text, "http://www.apache.org/licenses/") && strings.Contains(text, "END OF TERMS AND CONDITIONS") {
		return "Apache-2.0"
	}
	return ""
}
func skillEntry(c client.RunClaim) (string, string, string, error) {
	if c.Run.RunID == "" || c.Run.SourceID == "" || c.Config.SourceID != c.Run.SourceID || c.Config.ConfigVersion != c.Run.ConfigVersion || (c.Config.Config.ParserVersion != "github-skill-v1" && c.Config.Config.ParserVersion != "github-skill-v2") || len(c.Config.Config.EntryURLs) != 1 {
		return "", "", "", ErrSourceConfig
	}
	entry := c.Config.Config.EntryURLs[0]
	checked, err := sourceURL(entry, "", c.Config.Config.AllowedHosts)
	if err != nil {
		return "", "", "", err
	}
	u, _ := url.Parse(checked)
	if u.Host != "api.github.com" || u.RawPath != "" || u.RawQuery != "" || u.Fragment != "" {
		return "", "", "", ErrSourceConfig
	}
	parts := strings.Split(strings.TrimPrefix(u.Path, "/repos/"), "/contents/")
	if len(parts) != 2 || u.Path != "/repos/"+parts[0]+"/contents/"+parts[1] {
		return "", "", "", ErrSourceConfig
	}
	expected := map[string]string{"openai/skills": "skills/.curated/security-best-practices/SKILL.md", "anthropics/skills": "skills/skill-creator/SKILL.md", "vercel-labs/agent-skills": "skills/react-best-practices/SKILL.md"}
	if path, ok := expected[parts[0]]; !ok || path != parts[1] {
		return "", "", "", ErrSourceConfig
	}
	return entry, parts[0], parts[1], nil
}
func (a *SkillAdapter) state(c client.RunClaim, cursor string) (catalogCursor, error) {
	if a == nil {
		return catalogCursor{}, ErrSourceConfig
	}
	entry, _, _, err := skillEntry(c)
	if err != nil {
		return catalogCursor{}, err
	}
	if cursor == "" {
		return catalogCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: c.Run.ConfigVersion, Entry: entry, Gaps: []string{"missing_author_report", "independent_skill_score_unavailable"}}, nil
	}
	var state catalogCursor
	d := json.NewDecoder(strings.NewReader(cursor))
	d.DisallowUnknownFields()
	if len(cursor) > 8192 || checkedJSON([]byte(cursor)) != nil || d.Decode(&state) != nil || state.Run != c.Run.RunID || state.Source != c.Run.SourceID || state.Config != c.Run.ConfigVersion || state.Entry != entry || state.Step < 1 || state.Step > 4 || state.RepositoryID <= 0 || state.RepositoryID > 9007199254740991 || !repoPart(state.Branch) || state.Step >= 2 && (!gitCommit(state.Commit) || state.Updated == "") || len(state.Gaps) > 32 {
		return catalogCursor{}, ErrSourceConfig
	}
	if state.Updated != "" {
		if _, err := parseSourceTime(state.Updated); err != nil {
			return catalogCursor{}, ErrSourceConfig
		}
	}
	if state.LicensePath != "" && (!catalogPath(state.LicensePath) || len(state.LicenseHash) != 64) {
		return catalogCursor{}, ErrSourceConfig
	}
	for _, gap := range state.Gaps {
		switch gap {
		case "missing_author_report", "independent_skill_score_unavailable", "missing_license", "missing_skill_document", "skill_metadata_invalid", "document_truncated", "summary_truncated":
		default:
			return catalogCursor{}, ErrSourceConfig
		}
	}
	return state, nil
}
func (a *SkillAdapter) Completed(c client.RunClaim, cursor string) (bool, []string, error) {
	state, err := a.state(c, cursor)
	if err != nil {
		return false, nil, err
	}
	return state.Step == 4, append([]string(nil), state.Gaps...), nil
}
func (a *SkillAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	state, err := a.state(c, cursor)
	if err != nil || state.Step == 4 {
		return fetcher.Request{}, ErrSourceConfig
	}
	_, repo, path, _ := skillEntry(c)
	target := "https://api.github.com/repos/" + repo
	switch state.Step {
	case 1:
		target += "/commits?" + url.Values{"sha": {state.Branch}, "path": {path}, "per_page": {"1"}}.Encode()
	case 2:
		if c.Config.Config.ParserVersion == "github-skill-v2" {
			target += "/contents/" + skillLicensePath(repo, path) + "?ref=" + state.Commit
		} else {
			target += "/license?ref=" + state.Commit
		}
	case 3:
		target += "/contents/" + path + "?ref=" + state.Commit
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
func skillMetadata(data []byte) (string, string, []byte, error) {
	text := strings.ReplaceAll(string(data), "\r\n", "\n")
	if !strings.HasPrefix(text, "---\n") {
		return "", "", nil, ErrSourceDocument
	}
	end := strings.Index(text[4:], "\n---\n")
	if end < 0 || end > 16384 {
		return "", "", nil, ErrSourceDocument
	}
	end += 4
	d := yaml.NewDecoder(strings.NewReader(text[4:end]))
	var document yaml.Node
	if err := d.Decode(&document); err != nil || len(document.Content) != 1 || document.Content[0].Kind != yaml.MappingNode {
		return "", "", nil, ErrSourceDocument
	}
	var extra yaml.Node
	if d.Decode(&extra) != io.EOF {
		return "", "", nil, ErrSourceDocument
	}
	count := 0
	var valid func(*yaml.Node, int) bool
	valid = func(n *yaml.Node, depth int) bool {
		count++
		if depth > 16 || count > 4096 || n.Kind == yaml.AliasNode || n.Anchor != "" {
			return false
		}
		switch n.Tag {
		case "", "!!map", "!!seq", "!!str", "!!int", "!!bool", "!!float", "!!null", "!!timestamp":
		default:
			return false
		}
		if n.Kind == yaml.MappingNode {
			seen := map[string]bool{}
			for i := 0; i < len(n.Content); i += 2 {
				key := n.Content[i]
				if key.Kind != yaml.ScalarNode || key.Tag != "!!str" || key.Value == "<<" || seen[key.Value] {
					return false
				}
				seen[key.Value] = true
			}
		}
		for _, child := range n.Content {
			if !valid(child, depth+1) {
				return false
			}
		}
		return true
	}
	if !valid(&document, 0) {
		return "", "", nil, ErrSourceDocument
	}
	fields := map[string]string{}
	root := document.Content[0]
	for i := 0; i < len(root.Content); i += 2 {
		key, value := root.Content[i], root.Content[i+1]
		if key.Value == "name" || key.Value == "description" {
			if value.Kind != yaml.ScalarNode || value.Tag != "!!str" {
				return "", "", nil, ErrSourceDocument
			}
			fields[key.Value] = strings.TrimSpace(value.Value)
		}
	}
	if fields["name"] == "" || fields["description"] == "" {
		return "", "", nil, ErrSourceDocument
	}
	name, truncated := boundText(fields["name"], 512, 2048)
	if truncated {
		return "", "", nil, ErrSourceDocument
	}
	return name, fields["description"], []byte(text[end+5:]), nil
}
func (a *SkillAdapter) Parse(c client.RunClaim, response fetcher.Result) (scheduler.Page, error) {
	entry, repo, path, err := skillEntry(c)
	if err != nil || a == nil {
		return scheduler.Page{}, ErrSourceConfig
	}
	a.mu.Lock()
	pending, ok := a.pending[c.Run.RunID]
	a.mu.Unlock()
	if !ok || pending.state.Entry != entry || pending.state.Config != c.Run.ConfigVersion || response.FinalURL != pending.url || checkedJSON(response.Body) != nil || response.StatusCode != 200 && !(response.StatusCode == 404 && (pending.state.Step == 2 || pending.state.Step == 3)) {
		return scheduler.Page{}, ErrSourceDocument
	}
	state := pending.state
	items := []scheduler.Item{}
	switch state.Step {
	case 0:
		var value struct {
			ID       int64  `json:"id"`
			FullName string `json:"full_name"`
			Branch   string `json:"default_branch"`
		}
		if json.Unmarshal(response.Body, &value) != nil || value.ID <= 0 || value.ID > 9007199254740991 || value.FullName != repo || !repoPart(value.Branch) {
			return scheduler.Page{}, ErrSourceDocument
		}
		state.RepositoryID = value.ID
		state.Branch = value.Branch
	case 1:
		var commits []struct {
			SHA    string `json:"sha"`
			Commit struct {
				Committer struct {
					Date string `json:"date"`
				} `json:"committer"`
			} `json:"commit"`
		}
		if json.Unmarshal(response.Body, &commits) != nil || len(commits) != 1 || !gitCommit(commits[0].SHA) {
			return scheduler.Page{}, ErrSourceDocument
		}
		at, e := parseSourceTime(commits[0].Commit.Committer.Date)
		if e != nil || at.IsZero() {
			return scheduler.Page{}, ErrSourceDocument
		}
		state.Commit = commits[0].SHA
		state.Updated = wireTime(at)
	case 2:
		if response.StatusCode == 404 {
			state.Gaps = hnGap(state.Gaps, "missing_license")
		} else {
			licensePath, data, spdx, e := catalogFile(response.Body)
			if e != nil {
				return scheduler.Page{}, e
			}
			if c.Config.Config.ParserVersion == "github-skill-v2" {
				if licensePath != skillLicensePath(repo, path) {
					return scheduler.Page{}, ErrSourceDocument
				}
				spdx = skillLicenseDeclaration(repo, data)
			}
			hash := sha256.Sum256(data)
			state.LicensePath = licensePath
			state.LicenseHash = hex.EncodeToString(hash[:])
			if spdx == "" || spdx == "NOASSERTION" {
				state.Gaps = hnGap(state.Gaps, "missing_license")
			}
		}
	case 3:
		if response.StatusCode == 404 {
			state.Gaps = hnGap(state.Gaps, "missing_skill_document")
			break
		}
		actualPath, data, _, e := catalogFile(response.Body)
		if e != nil || actualPath != path {
			return scheduler.Page{}, ErrSourceDocument
		}
		name, description, body, e := skillMetadata(data)
		if e != nil {
			state.Gaps = hnGap(state.Gaps, "skill_metadata_invalid")
			break
		}
		summary, summaryTruncated := boundText(strings.Join(strings.Fields(htmlText(description)), " "), 8192, 8192)
		if summaryTruncated {
			state.Gaps = hnGap(state.Gaps, "summary_truncated")
		}
		var rendered bytes.Buffer
		if goldmark.Convert(body, &rendered) != nil {
			return scheduler.Page{}, ErrSourceDocument
		}
		document, truncated := boundText(strings.Join(strings.Fields(htmlText(rendered.String())), " "), 120000, 120*1024)
		if truncated {
			state.Gaps = hnGap(state.Gaps, "document_truncated")
		}
		hash := sha256.Sum256(data)
		version := hex.EncodeToString(hash[:])
		identity, err := client.CanonicalBytes(map[string]any{"repository_id": strconv.FormatInt(state.RepositoryID, 10), "path": path})
		if err != nil {
			return scheduler.Page{}, err
		}
		link := "https://github.com/" + repo + "/blob/" + state.Commit + "/" + path
		evidence := []any{catalogEvidence(link, state.Commit, path, version, "skill_document")}
		if state.LicensePath != "" {
			evidence = append(evidence, catalogEvidence("https://github.com/"+repo+"/blob/"+state.Commit+"/"+state.LicensePath, state.Commit, state.LicensePath, state.LicenseHash, "metadata"))
		}
		payload := map[string]any{"schema_version": "1", "parser_version": c.Config.Config.ParserVersion, "config_version": json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)), "category": "skill", "identity": map[string]any{"namespace": "github_skill", "key": string(identity)}, "title": name, "source_url": link, "version": version, "published_at": nil, "updated_at": state.Updated, "summary": summary, "document_text": document, "truncated": truncated, "evidence": evidence}
		if client.ValidateCatalogPayload(payload) != nil {
			return scheduler.Page{}, ErrSourceDocument
		}
		raw, _ := json.Marshal(payload)
		at, _ := parseSourceTime(state.Updated)
		items = append(items, scheduler.Item{Kind: "catalog", SourceItemID: string(identity), EvidenceTime: at, Version: version, VersionTime: at, EvidenceRef: link, Payload: raw})
	default:
		return scheduler.Page{}, ErrSourceConfig
	}
	state.Step++
	cursor, err := json.Marshal(state)
	if err != nil || len(cursor) > 8192 {
		return scheduler.Page{}, ErrSourceDocument
	}
	if state.Step == 4 {
		a.mu.Lock()
		delete(a.pending, c.Run.RunID)
		a.mu.Unlock()
	}
	return scheduler.Page{Items: items, NextCursor: string(cursor), End: state.Step == 4, Gaps: append([]string(nil), state.Gaps...)}, nil
}
func (a *SkillAdapter) Freeze(c client.RunClaim, item scheduler.Item, at time.Time) (outbox.Record, error) {
	_, repo, path, err := skillEntry(c)
	if err != nil {
		return outbox.Record{}, err
	}
	if item.Kind != "catalog" || at.IsZero() {
		return outbox.Record{}, ErrSourceDocument
	}
	parsed, err := client.ParseStrict(item.Payload)
	if err != nil {
		return outbox.Record{}, err
	}
	payload, ok := parsed.(map[string]any)
	if !ok || client.ValidateCatalogPayload(payload) != nil {
		return outbox.Record{}, ErrSourceDocument
	}
	identity := payload["identity"].(map[string]any)
	key, ok := identity["key"].(string)
	if !ok || key != item.SourceItemID {
		return outbox.Record{}, ErrSourceDocument
	}
	parsedKey, err := client.ParseStrict([]byte(key))
	if err != nil {
		return outbox.Record{}, err
	}
	keyObject, ok := parsedKey.(map[string]any)
	if !ok || len(keyObject) != 2 || keyObject["path"] != path {
		return outbox.Record{}, ErrSourceDocument
	}
	repoID, ok := keyObject["repository_id"].(string)
	if !ok {
		return outbox.Record{}, ErrSourceDocument
	}
	number, e := strconv.ParseInt(repoID, 10, 64)
	if e != nil || number <= 0 || number > 9007199254740991 || strconv.FormatInt(number, 10) != repoID {
		return outbox.Record{}, ErrSourceDocument
	}
	canonical, _ := client.CanonicalBytes(keyObject)
	if string(canonical) != key {
		return outbox.Record{}, ErrSourceDocument
	}
	link, ok := payload["source_url"].(string)
	prefix := "https://github.com/" + repo + "/blob/"
	if !ok || !strings.HasPrefix(link, prefix) || !strings.HasSuffix(link, "/"+path) {
		return outbox.Record{}, ErrSourceConfig
	}
	commit := strings.TrimSuffix(strings.TrimPrefix(link, prefix), "/"+path)
	if !gitCommit(commit) || payload["category"] != "skill" || payload["parser_version"] != c.Config.Config.ParserVersion || payload["config_version"] != json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)) {
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
	row := map[string]any{"protocol_revision": "0.11", "observation_id": id, "member_id": member, "run_id": c.Run.RunID, "business_date": c.Run.BusinessDate, "source_id": c.Run.SourceID, "source_revision": c.Run.ConfigVersion, "source_item_id": item.SourceItemID, "kind": "catalog", "retrieved_at": wireTime(at), "replaces_observation_id": nil, "payload": payload}
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
	recordKey, err := client.Hash(client.SourceItemHash, map[string]any{"source_id": c.Run.SourceID, "source_item_id": id})
	if err != nil {
		return outbox.Record{}, err
	}
	return outbox.Record{ID: id, Kind: "observation", RecordKey: recordKey, SourceID: c.Run.SourceID, RunID: c.Run.RunID, MemberID: member, ObservationID: id, Payload: wire, PayloadHash: hash, ProtocolRevision: "0.11", HashVersion: 1}, nil
}
