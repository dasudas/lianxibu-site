package parsers

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const hleEntry = "https://huggingface.co/api/datasets/OpenEvals/leaderboard-data"
const hleParser = "hf-hle-openevals-v1"

func uniqueStrings(values []string) []string {
	result := []string{}
	seen := map[string]bool{}
	for _, value := range values {
		if !seen[value] {
			result = append(result, value)
			seen[value] = true
		}
	}
	return result
}

type HLEAdapter struct {
	mu      sync.Mutex
	pending map[string]hlePending
}
type hleCursor struct {
	Run      string   `json:"run"`
	Source   string   `json:"source"`
	Config   uint64   `json:"config"`
	Revision string   `json:"revision"`
	FileHash string   `json:"file_hash"`
	Offset   int      `json:"offset"`
	Done     bool     `json:"done"`
	Gaps     []string `json:"gaps"`
}
type hlePending struct {
	state hleCursor
	url   string
}

var modelRegistryID = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,159}$`)
var _ scheduler.Adapter = (*HLEAdapter)(nil)
var _ scheduler.CompletedCursor = (*HLEAdapter)(nil)

func HLE() *HLEAdapter { return &HLEAdapter{pending: map[string]hlePending{}} }
func hleConfig(c client.RunClaim) error {
	if c.Run.RunID == "" || c.Run.SourceID == "" || c.Run.SourceID != c.Config.SourceID || c.Run.ConfigVersion != c.Config.ConfigVersion || c.Config.Config.ParserVersion != hleParser || len(c.Config.Config.EntryURLs) != 1 || c.Config.Config.EntryURLs[0] != hleEntry {
		return ErrSourceConfig
	}
	if _, e := sourceURL(hleEntry, "", c.Config.Config.AllowedHosts); e != nil {
		return e
	}
	return nil
}
func (a *HLEAdapter) state(c client.RunClaim, cursor string) (hleCursor, error) {
	if a == nil || hleConfig(c) != nil {
		return hleCursor{}, ErrSourceConfig
	}
	if cursor == "" {
		return hleCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: c.Run.ConfigVersion, Gaps: []string{"original_benchmark_version_unknown", "evaluation_configuration_unknown", "aggregation_source"}}, nil
	}
	var s hleCursor
	d := json.NewDecoder(strings.NewReader(cursor))
	d.DisallowUnknownFields()
	if len(cursor) > 4096 || checkedJSON([]byte(cursor)) != nil || d.Decode(&s) != nil || s.Run != c.Run.RunID || s.Source != c.Run.SourceID || s.Config != c.Run.ConfigVersion || !gitCommit(s.Revision) || s.Offset < 0 || s.Offset > 10000 || !s.Done && s.Offset%30 != 0 || len(s.Gaps) > 16 {
		return s, ErrSourceConfig
	}
	if s.Offset > 0 || s.Done {
		if !lowerHash(s.FileHash) {
			return s, ErrSourceConfig
		}
	} else if s.FileHash != "" {
		return s, ErrSourceConfig
	}
	for _, g := range s.Gaps {
		switch g {
		case "original_benchmark_version_unknown", "evaluation_configuration_unknown", "aggregation_source", "model_identity_invalid", "evaluation_time_unknown", "score_unknown":
		default:
			return s, ErrSourceConfig
		}
	}
	return s, nil
}
func lowerHash(v string) bool {
	if len(v) != 64 {
		return false
	}
	for _, r := range v {
		if !(r >= 'a' && r <= 'f' || r >= '0' && r <= '9') {
			return false
		}
	}
	return true
}
func hleFile(revision string) string {
	return "https://huggingface.co/datasets/OpenEvals/leaderboard-data/raw/" + revision + "/leaderboard.json"
}
func (a *HLEAdapter) Completed(c client.RunClaim, cursor string) (bool, []string, error) {
	s, e := a.state(c, cursor)
	return s.Done, append([]string(nil), s.Gaps...), e
}
func (a *HLEAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	s, e := a.state(c, cursor)
	if e != nil || s.Done {
		return fetcher.Request{}, ErrSourceConfig
	}
	u := hleEntry
	format := fetcher.JSON
	if s.Revision != "" {
		u = hleFile(s.Revision)
		format = fetcher.Text // The fixed raw JSON file is served as text/plain; Parse still checks JSON strictly.
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.pending) >= 100 {
		if _, ok := a.pending[c.Run.RunID]; !ok {
			return fetcher.Request{}, ErrSourceConfig
		}
	}
	a.pending[c.Run.RunID] = hlePending{s, u}
	return fetcher.Request{SourceID: c.Run.SourceID, URL: u, AllowedHosts: append([]string(nil), c.Config.Config.AllowedHosts...), Format: format, MinInterval: time.Second}, nil
}
func (a *HLEAdapter) Parse(c client.RunClaim, r fetcher.Result) (scheduler.Page, error) {
	if hleConfig(c) != nil {
		return scheduler.Page{}, ErrSourceConfig
	}
	a.mu.Lock()
	p, ok := a.pending[c.Run.RunID]
	a.mu.Unlock()
	if !ok || p.state.Source != c.Run.SourceID || p.state.Config != c.Run.ConfigVersion || r.StatusCode != 200 || r.FinalURL != p.url || len(r.Body) > 2*1024*1024 {
		return scheduler.Page{}, ErrSourceDocument
	}
	if checkedJSON(r.Body) != nil {
		return scheduler.Page{}, ErrSourceDocument
	}
	var obj map[string]any
	decoder := json.NewDecoder(strings.NewReader(string(r.Body)))
	decoder.UseNumber()
	if decoder.Decode(&obj) != nil || obj == nil {
		return scheduler.Page{}, ErrSourceDocument
	}
	s := p.state
	items := []scheduler.Item{}
	if s.Revision == "" {
		rev, _ := obj["sha"].(string)
		if obj["id"] != "OpenEvals/leaderboard-data" || !gitCommit(rev) || obj["gated"] != false {
			return scheduler.Page{}, ErrSourceDocument
		}
		siblings, ok := obj["siblings"].([]any)
		if !ok || len(siblings) > 1000 {
			return scheduler.Page{}, ErrSourceDocument
		}
		found := false
		for _, v := range siblings {
			if m, ok := v.(map[string]any); ok && m["rfilename"] == "leaderboard.json" {
				found = true
			}
		}
		if !found {
			return scheduler.Page{}, ErrSourceDocument
		}
		s.Revision = rev
	} else {
		sum := sha256.Sum256(r.Body)
		hash := hex.EncodeToString(sum[:])
		if s.FileHash != "" && s.FileHash != hash {
			return scheduler.Page{}, ErrSourceDocument
		}
		s.FileHash = hash
		meta, ok := obj["metadata"].(map[string]any)
		if !ok {
			return scheduler.Page{}, ErrSourceDocument
		}
		updated, _ := meta["lastUpdated"].(string)
		at, e := parseSourceTime(updated)
		if e != nil || at.IsZero() {
			return scheduler.Page{}, ErrSourceDocument
		}
		definitions, ok := obj["benchmarks"].(map[string]any)
		if !ok {
			return scheduler.Page{}, ErrSourceDocument
		}
		definition, ok := definitions["hle"].(map[string]any)
		if !ok || definition["id"] != "hle" || definition["metric"] != "Accuracy" || definition["metricUnit"] != "%" || definition["huggingfaceUrl"] != "https://huggingface.co/datasets/cais/hle" {
			return scheduler.Page{}, ErrSourceDocument
		}
		models, ok := obj["models"].([]any)
		if !ok || len(models) > 10000 || s.Offset > len(models) {
			return scheduler.Page{}, ErrSourceDocument
		}
		// Validate all identities before saving a page, including duplicates beyond its boundary.
		seen := map[string]bool{}
		for _, v := range models {
			m, ok := v.(map[string]any)
			if !ok {
				return scheduler.Page{}, ErrSourceDocument
			}
			name, _ := m["name"].(string)
			if !modelRegistryID.MatchString(name) {
				continue
			}
			if seen[name] {
				return scheduler.Page{}, ErrSourceDocument
			}
			seen[name] = true
		}
		end := s.Offset + 30
		if end > len(models) {
			end = len(models)
		}
		for _, v := range models[s.Offset:end] {
			m := v.(map[string]any)
			name, _ := m["name"].(string)
			if !modelRegistryID.MatchString(name) {
				s.Gaps = uniqueStrings(append(s.Gaps, "model_identity_invalid"))
				continue
			}
			payload, e := hleMetric(c, m, s, r.FinalURL)
			if e != nil {
				return scheduler.Page{}, e
			}
			if payload["score"] == nil {
				s.Gaps = uniqueStrings(append(s.Gaps, "score_unknown"))
			}
			s.Gaps = uniqueStrings(append(s.Gaps, "evaluation_time_unknown"))
			raw, e := json.Marshal(payload)
			if e != nil {
				return scheduler.Page{}, e
			}
			key := hleKey(name)
			material := map[string]any{}
			for k, v := range payload {
				if k != "evidence" {
					material[k] = v
				}
			}
			canonical, e := client.CanonicalBytes(material)
			if e != nil {
				return scheduler.Page{}, e
			}
			versionHash := sha256.Sum256(canonical)
			items = append(items, scheduler.Item{Kind: "metric", SourceItemID: key, EvidenceTime: at, Version: hex.EncodeToString(versionHash[:]), EvidenceRef: r.FinalURL, Payload: raw})
		}
		s.Offset = end
		s.Done = end == len(models)
	}
	cursor, e := json.Marshal(s)
	if e != nil {
		return scheduler.Page{}, e
	}
	if s.Done {
		a.mu.Lock()
		delete(a.pending, c.Run.RunID)
		a.mu.Unlock()
	}
	return scheduler.Page{Items: items, NextCursor: string(cursor), End: s.Done, Gaps: append([]string(nil), s.Gaps...)}, nil
}
func hleKey(name string) string {
	raw, _ := client.CanonicalBytes(map[string]any{"model": name, "benchmark": "hle", "task": "accuracy"})
	return string(raw)
}
func hleMetric(c client.RunClaim, m map[string]any, s hleCursor, link string) (map[string]any, error) {
	name := m["name"].(string)
	benchmarks, ok := m["benchmarks"].(map[string]any)
	if !ok {
		return nil, ErrSourceDocument
	}
	var score any
	parameters := map[string]any{"version_scope": "aggregation_snapshot", "original_benchmark_version": "unknown", "configuration_status": "unknown", "aggregation_dataset": "OpenEvals/leaderboard-data"}
	if value := benchmarks["hle"]; value != nil {
		metric, ok := value.(map[string]any)
		if !ok {
			return nil, ErrSourceDocument
		}
		if raw := metric["score"]; raw != nil {
			number, ok := raw.(json.Number)
			if !ok {
				return nil, ErrSourceDocument
			}
			score = number.String()
		}
		for _, field := range []string{"confidence", "source", "date"} {
			if v := metric[field]; v != nil {
				str, ok := v.(string)
				if !ok || len(str) > 256 {
					return nil, ErrSourceDocument
				}
				key := map[string]string{"confidence": "reported_confidence", "source": "reported_source", "date": "reported_date"}[field]
				parameters[key] = str
			}
		}
		if date, ok := parameters["reported_date"].(string); ok && date != "" {
			if _, e := time.Parse("2006-01-02", date); e != nil {
				return nil, ErrSourceDocument
			}
		}
	}
	payload := map[string]any{"schema_version": "1", "parser_version": hleParser, "config_version": json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)), "subject": map[string]any{"category": "model", "identity": map[string]any{"namespace": "model_registry", "key": name}}, "benchmark": "hle", "benchmark_version": "aggregation_snapshot:" + s.Revision, "task": "accuracy", "score": score, "unit": "percent", "scale_min": "0", "scale_max": "100", "higher_is_better": true, "evaluated_at": nil, "evaluation_scope": "entity", "evaluation_config": map[string]any{"agent": nil, "model": name, "skills": []any{}, "parameters": parameters}, "provenance": "third_party", "evidence": []any{catalogEvidence(link, s.Revision, "leaderboard.json", s.FileHash, "benchmark")}}
	if err := client.ValidateMetricPayload(payload); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrSourceDocument, err)
	}
	return payload, nil
}
func (a *HLEAdapter) Freeze(c client.RunClaim, item scheduler.Item, at time.Time) (outbox.Record, error) {
	if hleConfig(c) != nil {
		return outbox.Record{}, ErrSourceConfig
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
	identity := subject["identity"].(map[string]any)
	name := identity["key"].(string)
	revision := strings.TrimPrefix(v["benchmark_version"].(string), "aggregation_snapshot:")
	if !modelRegistryID.MatchString(name) || !strings.HasPrefix(v["benchmark_version"].(string), "aggregation_snapshot:") || !gitCommit(revision) || item.SourceItemID != hleKey(name) || subject["category"] != "model" || v["parser_version"] != hleParser || v["config_version"] != json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)) || v["benchmark"] != "hle" || v["task"] != "accuracy" || v["provenance"] != "third_party" || v["unit"] != "percent" || v["higher_is_better"] != true || v["evaluated_at"] != nil || v["evaluation_scope"] != "entity" {
		return outbox.Record{}, ErrSourceConfig
	}
	config := v["evaluation_config"].(map[string]any)
	params := config["parameters"].(map[string]any)
	if config["model"] != name || config["agent"] != nil || len(config["skills"].([]any)) != 0 || params["version_scope"] != "aggregation_snapshot" || params["original_benchmark_version"] != "unknown" || params["configuration_status"] != "unknown" || params["aggregation_dataset"] != "OpenEvals/leaderboard-data" {
		return outbox.Record{}, ErrSourceDocument
	}
	evidence := v["evidence"].([]any)
	if len(evidence) != 1 {
		return outbox.Record{}, ErrSourceDocument
	}
	ref := evidence[0].(map[string]any)
	hash, ok := ref["file_hash"].(string)
	if !ok || !lowerHash(hash) || ref["url"] != hleFile(revision) || ref["revision"] != revision || ref["path"] != "leaderboard.json" || ref["evidence_type"] != "benchmark" {
		return outbox.Record{}, ErrSourceDocument
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
	digest, e := client.Hash(client.ObservationHash, row)
	if e != nil {
		return outbox.Record{}, e
	}
	row["payload_hash"] = digest
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
	return outbox.Record{ID: id, Kind: "observation", RecordKey: key, SourceID: c.Run.SourceID, RunID: c.Run.RunID, MemberID: member, ObservationID: id, Payload: wire, PayloadHash: digest, ProtocolRevision: "0.11", HashVersion: 1}, nil
}
