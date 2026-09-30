package parsers

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"io"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const livebenchCommitEntry = "https://api.github.com/repos/LiveBench/new-livebench/commits/main"
const livebenchRawBase = "https://raw.githubusercontent.com/LiveBench/new-livebench/"

type LiveBenchAdapter struct {
	mu      sync.Mutex
	pending map[string]livebenchPending
}
type livebenchCursor struct {
	Run          string            `json:"run"`
	Source       string            `json:"source"`
	Config       uint64            `json:"config"`
	Release      string            `json:"release"`
	Step         int               `json:"step"`
	Commit       string            `json:"commit"`
	Updated      string            `json:"updated"`
	Categories   map[string]string `json:"categories"`
	Models       map[string]string `json:"models"`
	CategoryHash string            `json:"category_hash"`
	ModelHash    string            `json:"model_hash"`
	TableHash    string            `json:"table_hash"`
	Offset       int               `json:"offset"`
	Gaps         []string          `json:"gaps"`
}
type livebenchPending struct {
	state livebenchCursor
	url   string
}
type livebenchDatum struct {
	rawModel, model, task string
	score                 any
}

var livebenchEntryPattern = regexp.MustCompile(`^https://raw\.githubusercontent\.com/LiveBench/new-livebench/main/public/table_([0-9]{4}_[0-9]{2}_[0-9]{2})\.csv$`)
var livebenchTask = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]{0,127}$`)
var _ scheduler.Adapter = (*LiveBenchAdapter)(nil)
var _ scheduler.CompletedCursor = (*LiveBenchAdapter)(nil)

func LiveBench() *LiveBenchAdapter { return &LiveBenchAdapter{pending: map[string]livebenchPending{}} }
func livebenchRelease(c client.RunClaim) (string, error) {
	if c.Run.RunID == "" || c.Run.SourceID == "" || c.Config.SourceID != c.Run.SourceID || c.Config.ConfigVersion != c.Run.ConfigVersion || c.Config.Config.ParserVersion != "livebench-v1" || len(c.Config.Config.EntryURLs) != 2 || c.Config.Config.EntryURLs[0] != livebenchCommitEntry {
		return "", ErrSourceConfig
	}
	match := livebenchEntryPattern.FindStringSubmatch(c.Config.Config.EntryURLs[1])
	if match == nil {
		return "", ErrSourceConfig
	}
	date := strings.ReplaceAll(match[1], "_", "-")
	if _, e := time.Parse("2006-01-02", date); e != nil {
		return "", ErrSourceConfig
	}
	for _, entry := range c.Config.Config.EntryURLs {
		if _, e := sourceURL(entry, "", c.Config.Config.AllowedHosts); e != nil {
			return "", e
		}
	}
	return date, nil
}
func (a *LiveBenchAdapter) state(c client.RunClaim, cursor string) (livebenchCursor, error) {
	release, e := livebenchRelease(c)
	if a == nil || e != nil {
		return livebenchCursor{}, ErrSourceConfig
	}
	if cursor == "" {
		return livebenchCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: c.Run.ConfigVersion, Release: release, Gaps: []string{"license_unverified", "evaluation_time_unknown", "evaluation_configuration_unknown"}}, nil
	}
	var s livebenchCursor
	d := json.NewDecoder(strings.NewReader(cursor))
	d.DisallowUnknownFields()
	if len(cursor) > 256*1024 || checkedJSON([]byte(cursor)) != nil || d.Decode(&s) != nil || s.Run != c.Run.RunID || s.Source != c.Run.SourceID || s.Config != c.Run.ConfigVersion || s.Release != release || s.Step < 1 || s.Step > 4 || !gitCommit(s.Commit) || s.Offset < 0 || s.Offset > 100000 || s.Step < 4 && s.Offset%50 != 0 || len(s.Gaps) > 10 {
		return s, ErrSourceConfig
	}
	at, e := parseSourceTime(s.Updated)
	if e != nil || at.IsZero() {
		return s, ErrSourceConfig
	}
	if s.Step >= 2 && (len(s.Categories) == 0 || len(s.Categories) > 100 || !lowerHash(s.CategoryHash)) {
		return s, ErrSourceConfig
	}
	for task, category := range s.Categories {
		if !livebenchTask.MatchString(task) || category == "" || len(category) > 128 {
			return s, ErrSourceConfig
		}
	}
	if s.Step >= 3 && (len(s.Models) > 1000 || !lowerHash(s.ModelHash)) {
		return s, ErrSourceConfig
	}
	for key, model := range s.Models {
		if key == "" || len(key) > 256 || !modelRegistryID.MatchString(model) {
			return s, ErrSourceConfig
		}
	}
	if s.Offset > 0 || s.Step == 4 {
		if !lowerHash(s.TableHash) {
			return s, ErrSourceConfig
		}
	} else if s.TableHash != "" {
		return s, ErrSourceConfig
	}
	for _, g := range s.Gaps {
		switch g {
		case "license_unverified", "evaluation_time_unknown", "evaluation_configuration_unknown", "model_identity_unmapped", "score_unknown":
		default:
			return s, ErrSourceConfig
		}
	}
	return s, nil
}
func (a *LiveBenchAdapter) Completed(c client.RunClaim, cursor string) (bool, []string, error) {
	s, e := a.state(c, cursor)
	return s.Step == 4, append([]string(nil), s.Gaps...), e
}
func (a *LiveBenchAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	s, e := a.state(c, cursor)
	if e != nil || s.Step == 4 {
		return fetcher.Request{}, ErrSourceConfig
	}
	path := ""
	format := fetcher.Text
	switch s.Step {
	case 0:
		path = livebenchCommitEntry
		format = fetcher.JSON
	case 1:
		path = livebenchRawBase + s.Commit + "/public/categories_" + strings.ReplaceAll(s.Release, "-", "_") + ".json"
		format = fetcher.JSON
	case 2:
		path = livebenchRawBase + s.Commit + "/src/Table/modelLinks.js"
	case 3:
		path = livebenchRawBase + s.Commit + "/public/table_" + strings.ReplaceAll(s.Release, "-", "_") + ".csv"
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.pending) >= 100 {
		if _, ok := a.pending[c.Run.RunID]; !ok {
			return fetcher.Request{}, ErrSourceConfig
		}
	}
	a.pending[c.Run.RunID] = livebenchPending{s, path}
	return fetcher.Request{SourceID: c.Run.SourceID, URL: path, AllowedHosts: append([]string(nil), c.Config.Config.AllowedHosts...), Format: format, MinInterval: time.Second}, nil
}
func rawSHA256(raw []byte) string { sum := sha256.Sum256(raw); return hex.EncodeToString(sum[:]) }
func (a *LiveBenchAdapter) Parse(c client.RunClaim, r fetcher.Result) (scheduler.Page, error) {
	release, e := livebenchRelease(c)
	if e != nil {
		return scheduler.Page{}, e
	}
	a.mu.Lock()
	p, ok := a.pending[c.Run.RunID]
	a.mu.Unlock()
	if !ok || p.state.Source != c.Run.SourceID || p.state.Config != c.Run.ConfigVersion || p.state.Release != release || r.StatusCode != 200 || r.FinalURL != p.url || len(r.Body) > 2*1024*1024 {
		return scheduler.Page{}, ErrSourceDocument
	}
	s := p.state
	items := []scheduler.Item{}
	switch s.Step {
	case 0:
		if checkedJSON(r.Body) != nil {
			return scheduler.Page{}, ErrSourceDocument
		}
		var doc struct {
			SHA    string `json:"sha"`
			Commit struct {
				Committer struct {
					Date string `json:"date"`
				} `json:"committer"`
			} `json:"commit"`
		}
		if json.Unmarshal(r.Body, &doc) != nil || !gitCommit(doc.SHA) {
			return scheduler.Page{}, ErrSourceDocument
		}
		at, e := parseSourceTime(doc.Commit.Committer.Date)
		if e != nil || at.IsZero() {
			return scheduler.Page{}, ErrSourceDocument
		}
		s.Commit = doc.SHA
		s.Updated = wireTime(at)
		s.Step = 1
	case 1:
		if checkedJSON(r.Body) != nil {
			return scheduler.Page{}, ErrSourceDocument
		}
		var categories map[string][]string
		if json.Unmarshal(r.Body, &categories) != nil || len(categories) == 0 || len(categories) > 32 {
			return scheduler.Page{}, ErrSourceDocument
		}
		s.Categories = map[string]string{}
		for category, tasks := range categories {
			if category == "" || len(category) > 128 || len(tasks) == 0 || len(tasks) > 100 {
				return scheduler.Page{}, ErrSourceDocument
			}
			for _, task := range tasks {
				if !livebenchTask.MatchString(task) || s.Categories[task] != "" {
					return scheduler.Page{}, ErrSourceDocument
				}
				s.Categories[task] = category
			}
		}
		if len(s.Categories) > 100 {
			return scheduler.Page{}, ErrSourceDocument
		}
		s.CategoryHash = rawSHA256(r.Body)
		s.Step = 2
	case 2:
		models, e := livebenchMappings(r.Body)
		if e != nil {
			return scheduler.Page{}, e
		}
		s.Models = models
		s.ModelHash = rawSHA256(r.Body)
		s.Step = 3
	case 3:
		hash := rawSHA256(r.Body)
		if s.TableHash != "" && s.TableHash != hash {
			return scheduler.Page{}, ErrSourceDocument
		}
		s.TableHash = hash
		data, gaps, e := livebenchTable(r.Body, s)
		if e != nil {
			return scheduler.Page{}, e
		}
		s.Gaps = uniqueStrings(append(s.Gaps, gaps...))
		if s.Offset > len(data) {
			return scheduler.Page{}, ErrSourceDocument
		}
		end := s.Offset + 50
		if end > len(data) {
			end = len(data)
		}
		at, _ := parseSourceTime(s.Updated)
		for _, datum := range data[s.Offset:end] {
			payload := livebenchMetric(c, s, datum)
			if client.ValidateMetricPayload(payload) != nil {
				return scheduler.Page{}, ErrSourceDocument
			}
			raw, e := json.Marshal(payload)
			if e != nil {
				return scheduler.Page{}, e
			}
			key := livebenchKey(datum.rawModel, datum.model, datum.task)
			material := map[string]any{}
			for key, value := range payload {
				if key != "evidence" {
					material[key] = value
				}
			}
			canonical, e := client.CanonicalBytes(material)
			if e != nil {
				return scheduler.Page{}, e
			}
			items = append(items, scheduler.Item{Kind: "metric", SourceItemID: key, EvidenceTime: at, Version: rawSHA256(canonical), EvidenceRef: p.url, Payload: raw})
		}
		s.Offset = end
		if end == len(data) {
			s.Step = 4
		}
	default:
		return scheduler.Page{}, ErrSourceConfig
	}
	cursor, e := json.Marshal(s)
	if e != nil || len(cursor) > 256*1024 {
		return scheduler.Page{}, ErrSourceDocument
	}
	if s.Step == 4 {
		a.mu.Lock()
		delete(a.pending, c.Run.RunID)
		a.mu.Unlock()
	}
	return scheduler.Page{Items: items, NextCursor: string(cursor), End: s.Step == 4, Gaps: append([]string(nil), s.Gaps...)}, nil
}
func livebenchTable(raw []byte, s livebenchCursor) ([]livebenchDatum, []string, error) {
	if !utf8.Valid(raw) {
		return nil, nil, ErrSourceDocument
	}
	reader := csv.NewReader(bytes.NewReader(raw))
	reader.ReuseRecord = false
	header, e := reader.Read()
	if e != nil || len(header) < 2 || len(header) > 101 || header[0] != "model" || len(header)-1 != len(s.Categories) {
		return nil, nil, ErrSourceDocument
	}
	seen := map[string]bool{}
	for _, task := range header[1:] {
		if seen[task] || s.Categories[task] == "" {
			return nil, nil, ErrSourceDocument
		}
		seen[task] = true
	}
	data := []livebenchDatum{}
	gaps := []string{}
	seenModels := map[string]bool{}
	count := 0
	for {
		row, e := reader.Read()
		if e == io.EOF {
			break
		}
		count++
		if e != nil || count > 1000 || row[0] == "" || len(row[0]) > 256 || seenModels[row[0]] {
			return nil, nil, ErrSourceDocument
		}
		seenModels[row[0]] = true
		model := s.Models[row[0]]
		if model == "" {
			gaps = uniqueStrings(append(gaps, "model_identity_unmapped"))
			continue
		}
		for i, task := range header[1:] {
			var score any
			if row[i+1] != "" {
				score = row[i+1]
			} else {
				gaps = uniqueStrings(append(gaps, "score_unknown"))
			}
			datum := livebenchDatum{row[0], model, task, score}
			payload := livebenchMetric(client.RunClaim{}, s, datum)
			payload["config_version"] = json.Number("1")
			if client.ValidateMetricPayload(payload) != nil {
				return nil, nil, ErrSourceDocument
			}
			data = append(data, datum)
		}
	}
	return data, gaps, nil
}
func livebenchKey(raw, model, task string) string {
	v, _ := client.CanonicalBytes(map[string]any{"benchmark": "livebench", "model": model, "source_model_id": raw, "task": task})
	return string(v)
}
func livebenchMetric(c client.RunClaim, s livebenchCursor, d livebenchDatum) map[string]any {
	table := "public/table_" + strings.ReplaceAll(s.Release, "-", "_") + ".csv"
	categories := "public/categories_" + strings.ReplaceAll(s.Release, "-", "_") + ".json"
	models := "src/Table/modelLinks.js"
	evidence := []any{}
	for _, entry := range []struct{ path, hash string }{{table, s.TableHash}, {categories, s.CategoryHash}, {models, s.ModelHash}} {
		evidence = append(evidence, catalogEvidence(livebenchRawBase+s.Commit+"/"+entry.path, s.Commit, entry.path, entry.hash, "benchmark"))
	}
	return map[string]any{"schema_version": "1", "parser_version": "livebench-v1", "config_version": json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)), "subject": map[string]any{"category": "model", "identity": map[string]any{"namespace": "model_registry", "key": d.model}}, "benchmark": "livebench", "benchmark_version": s.Release, "task": d.task, "score": d.score, "unit": "percent", "scale_min": "0", "scale_max": "100", "higher_is_better": true, "evaluated_at": nil, "evaluation_scope": "configuration", "evaluation_config": map[string]any{"agent": nil, "model": d.model, "skills": []any{}, "parameters": map[string]any{"source_model_id": d.rawModel, "category": s.Categories[d.task], "configuration_status": "unknown", "release_date": s.Release}}, "provenance": "third_party", "evidence": evidence}
}
func (a *LiveBenchAdapter) Freeze(c client.RunClaim, item scheduler.Item, at time.Time) (outbox.Record, error) {
	release, e := livebenchRelease(c)
	if e != nil {
		return outbox.Record{}, e
	}
	if item.Kind != "metric" || at.IsZero() {
		return outbox.Record{}, ErrSourceDocument
	}
	parsed, e := client.ParseStrict(item.Payload)
	if e != nil {
		return outbox.Record{}, e
	}
	v, ok := parsed.(map[string]any)
	if !ok || client.ValidateMetricPayload(v) != nil {
		return outbox.Record{}, ErrSourceDocument
	}
	subject := v["subject"].(map[string]any)
	model := subject["identity"].(map[string]any)["key"].(string)
	config := v["evaluation_config"].(map[string]any)
	params := config["parameters"].(map[string]any)
	rawModel, _ := params["source_model_id"].(string)
	task := v["task"].(string)
	if !modelRegistryID.MatchString(model) || !livebenchTask.MatchString(task) || rawModel == "" || len(rawModel) > 256 || item.SourceItemID != livebenchKey(rawModel, model, task) || v["parser_version"] != "livebench-v1" || v["config_version"] != json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)) || subject["category"] != "model" || v["benchmark"] != "livebench" || v["benchmark_version"] != release || v["unit"] != "percent" || v["higher_is_better"] != true || v["evaluated_at"] != nil || v["evaluation_scope"] != "configuration" || v["provenance"] != "third_party" || config["model"] != model || config["agent"] != nil || len(config["skills"].([]any)) != 0 || params["configuration_status"] != "unknown" || params["release_date"] != release {
		return outbox.Record{}, ErrSourceDocument
	}
	refs := v["evidence"].([]any)
	if len(refs) != 3 {
		return outbox.Record{}, ErrSourceDocument
	}
	paths := []string{"public/table_" + strings.ReplaceAll(release, "-", "_") + ".csv", "public/categories_" + strings.ReplaceAll(release, "-", "_") + ".json", "src/Table/modelLinks.js"}
	commit := ""
	for i, entry := range refs {
		ref := entry.(map[string]any)
		revision, ok := ref["revision"].(string)
		hash, hashOK := ref["file_hash"].(string)
		if !ok || !gitCommit(revision) || !hashOK || !lowerHash(hash) || ref["path"] != paths[i] || ref["url"] != livebenchRawBase+revision+"/"+paths[i] || ref["evidence_type"] != "benchmark" || commit != "" && commit != revision {
			return outbox.Record{}, ErrSourceDocument
		}
		commit = revision
	}
	var random [16]byte
	if _, e = rand.Read(random[:]); e != nil {
		return outbox.Record{}, e
	}
	id := hex.EncodeToString(random[:])
	member, e := client.Hash(client.SourceItemHash, map[string]any{"source_id": c.Run.SourceID, "source_item_id": item.SourceItemID})
	if e != nil {
		return outbox.Record{}, e
	}
	row := map[string]any{"protocol_revision": "0.11", "observation_id": id, "member_id": member, "run_id": c.Run.RunID, "business_date": c.Run.BusinessDate, "source_id": c.Run.SourceID, "source_revision": c.Run.ConfigVersion, "source_item_id": item.SourceItemID, "kind": "metric", "retrieved_at": wireTime(at), "replaces_observation_id": nil, "payload": v}
	hash, e := client.Hash(client.ObservationHash, row)
	if e != nil {
		return outbox.Record{}, e
	}
	row["payload_hash"] = hash
	wire, e := json.Marshal(row)
	if e != nil {
		return outbox.Record{}, e
	}
	if _, e = client.DecodeObservation(wire); e != nil {
		return outbox.Record{}, e
	}
	key, e := client.Hash(client.SourceItemHash, map[string]any{"source_id": c.Run.SourceID, "source_item_id": id})
	if e != nil {
		return outbox.Record{}, e
	}
	return outbox.Record{ID: id, Kind: "observation", RecordKey: key, SourceID: c.Run.SourceID, RunID: c.Run.RunID, MemberID: member, ObservationID: id, Payload: wire, PayloadHash: hash, ProtocolRevision: "0.11", HashVersion: 1}, nil
}

// The small reader consumes static object literals as text. It never evaluates JavaScript.
func livebenchMappings(raw []byte) (map[string]string, error) {
	if len(raw) == 0 || len(raw) > 2*1024*1024 || !utf8.Valid(raw) {
		return nil, ErrSourceDocument
	}
	text, e := jsWithoutComments(string(raw))
	if e != nil {
		return nil, e
	}
	start := regexp.MustCompile(`(?m)^\s*export\s+const\s+modelLinks\s*=\s*\{`).FindStringIndex(text)
	if start == nil {
		return nil, ErrSourceDocument
	}
	root := text[start[1]-1:]
	end, e := jsObjectEnd(root)
	if e != nil {
		return nil, e
	}
	fields, e := jsFields(root[:end])
	if e != nil || len(fields) > 1000 {
		return nil, ErrSourceDocument
	}
	result := map[string]string{}
	for key, value := range fields {
		var name string
		if json.Unmarshal([]byte(key), &name) != nil || name == "" || len(name) > 256 {
			return nil, ErrSourceDocument
		}
		value = strings.TrimSpace(value)
		if !strings.HasPrefix(value, "{") {
			continue
		}
		properties, e := jsFields(value)
		if e != nil {
			return nil, e
		}
		link := ""
		for _, field := range []string{"huggingface", "url"} {
			for property, val := range properties {
				var prop string
				if strings.HasPrefix(property, `"`) {
					if json.Unmarshal([]byte(property), &prop) != nil {
						return nil, ErrSourceDocument
					}
				} else {
					prop = property
				}
				if prop == field {
					var candidate string
					if json.Unmarshal([]byte(val), &candidate) == nil {
						u, e := url.Parse(candidate)
						if e == nil && u.Scheme == "https" && u.Host == "huggingface.co" && u.User == nil && u.RawQuery == "" && u.Fragment == "" && u.RawPath == "" && modelRegistryID.MatchString(strings.TrimPrefix(u.Path, "/")) {
							link = strings.TrimPrefix(u.Path, "/")
						}
					}
				}
			}
			if link != "" {
				break
			}
		}
		if link != "" {
			result[name] = link
		}
	}
	return result, nil
}
func jsWithoutComments(text string) (string, error) {
	out := []byte(text)
	quote := byte(0)
	for i := 0; i < len(out); i++ {
		c := out[i]
		if quote != 0 {
			if c == '\\' {
				i++
				continue
			}
			if c == quote {
				quote = 0
			}
			continue
		}
		if c == '"' || c == '\'' || c == '`' {
			quote = c
			continue
		}
		if c == '/' && i+1 < len(out) {
			if out[i+1] == '/' {
				for i < len(out) && out[i] != '\n' {
					out[i] = ' '
					i++
				}
				i--
				continue
			}
			if out[i+1] == '*' {
				out[i] = ' '
				out[i+1] = ' '
				i += 2
				found := false
				for i+1 < len(out) {
					if out[i] == '*' && out[i+1] == '/' {
						out[i] = ' '
						out[i+1] = ' '
						i++
						found = true
						break
					}
					out[i] = ' '
					i++
				}
				if !found {
					return "", ErrSourceDocument
				}
			}
		}
	}
	if quote != 0 {
		return "", ErrSourceDocument
	}
	return string(out), nil
}
func jsObjectEnd(text string) (int, error) {
	if !strings.HasPrefix(text, "{") {
		return 0, ErrSourceDocument
	}
	stack := []byte{}
	quote := byte(0)
	for i := 0; i < len(text); i++ {
		c := text[i]
		if quote != 0 {
			if c == '\\' {
				i++
				continue
			}
			if c == quote {
				quote = 0
			}
			continue
		}
		if c == '"' || c == '\'' || c == '`' {
			quote = c
			continue
		}
		if c == '{' || c == '[' {
			stack = append(stack, c)
			if len(stack) > 64 {
				return 0, ErrSourceDocument
			}
		} else if c == '}' || c == ']' {
			if len(stack) == 0 || c == '}' && stack[len(stack)-1] != '{' || c == ']' && stack[len(stack)-1] != '[' {
				return 0, ErrSourceDocument
			}
			stack = stack[:len(stack)-1]
			if len(stack) == 0 {
				return i + 1, nil
			}
		}
	}
	return 0, ErrSourceDocument
}
func jsFields(text string) (map[string]string, error) {
	end, e := jsObjectEnd(text)
	if e != nil || strings.TrimSpace(text[end:]) != "" {
		return nil, ErrSourceDocument
	}
	body := text[1 : end-1]
	chunks := []string{}
	start := 0
	depth := 0
	quote := byte(0)
	for i := 0; i < len(body); i++ {
		c := body[i]
		if quote != 0 {
			if c == '\\' {
				i++
				continue
			}
			if c == quote {
				quote = 0
			}
			continue
		}
		if c == '"' || c == '\'' || c == '`' {
			quote = c
			continue
		}
		if c == '{' || c == '[' {
			depth++
		} else if c == '}' || c == ']' {
			depth--
		} else if c == ',' && depth == 0 {
			chunks = append(chunks, body[start:i])
			start = i + 1
		}
	}
	chunks = append(chunks, body[start:])
	result := map[string]string{}
	for _, chunk := range chunks {
		chunk = strings.TrimSpace(chunk)
		if chunk == "" {
			continue
		}
		colon := -1
		quote = 0
		for i := 0; i < len(chunk); i++ {
			if quote != 0 {
				if chunk[i] == '\\' {
					i++
					continue
				}
				if chunk[i] == quote {
					quote = 0
				}
				continue
			}
			if chunk[i] == '"' || chunk[i] == '\'' {
				quote = chunk[i]
			} else if chunk[i] == ':' {
				colon = i
				break
			}
		}
		if colon < 1 {
			return nil, ErrSourceDocument
		}
		key := strings.TrimSpace(chunk[:colon])
		value := strings.TrimSpace(chunk[colon+1:])
		if value == "" {
			return nil, ErrSourceDocument
		}
		normalized := key
		var decoded string
		if json.Unmarshal([]byte(key), &decoded) == nil {
			normalized = decoded
		}
		for existing := range result {
			var other string
			if json.Unmarshal([]byte(existing), &other) != nil {
				other = existing
			}
			if other == normalized {
				return nil, ErrSourceDocument
			}
		}
		result[key] = value
	}
	return result, nil
}
