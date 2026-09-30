package parsers

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"strconv"
	"strings"
	"sync"
	"time"
)

const skillsbenchEntry = "https://huggingface.co/api/datasets/benchflow/skillsbench-leaderboard?expand%5B%5D=sha&expand%5B%5D=gated&expand%5B%5D=lastModified"
const skillsbenchFilePath = "leaderboard/skillsbench/v1.1/official.json"

var skillsbenchAgents = []string{"codex", "claude-code", "gemini-cli"}

func skillsbenchRepository(agent string) string {
	return map[string]string{"codex": "openai/codex", "claude-code": "anthropics/claude-code", "gemini-cli": "google-gemini/gemini-cli"}[agent]
}

type SkillsBenchAdapter struct {
	mu      sync.Mutex
	pending map[string]skillsbenchPending
}
type skillsbenchOwner struct {
	ID   string `json:"id"`
	Hash string `json:"hash"`
}
type skillsbenchCursor struct {
	Run      string                      `json:"run"`
	Source   string                      `json:"source"`
	Config   uint64                      `json:"config"`
	Step     int                         `json:"step"`
	Revision string                      `json:"revision"`
	Updated  string                      `json:"updated"`
	Owners   map[string]skillsbenchOwner `json:"owners"`
	Hash     string                      `json:"hash"`
	Offset   int                         `json:"offset"`
	Gaps     []string                    `json:"gaps"`
}
type skillsbenchPending struct {
	state skillsbenchCursor
	url   string
}

var _ scheduler.Adapter = (*SkillsBenchAdapter)(nil)
var _ scheduler.CompletedCursor = (*SkillsBenchAdapter)(nil)

func SkillsBench() *SkillsBenchAdapter {
	return &SkillsBenchAdapter{pending: map[string]skillsbenchPending{}}
}
func skillsbenchConfig(c client.RunClaim) error {
	if c.Run.RunID == "" || c.Run.SourceID == "" || c.Config.SourceID != c.Run.SourceID || c.Config.ConfigVersion != c.Run.ConfigVersion || c.Config.Config.ParserVersion != "skillsbench-v1" || len(c.Config.Config.EntryURLs) != 1 || c.Config.Config.EntryURLs[0] != skillsbenchEntry {
		return ErrSourceConfig
	}
	for _, link := range []string{skillsbenchEntry, "https://api.github.com/repos/openai/codex"} {
		if _, e := sourceURL(link, "", c.Config.Config.AllowedHosts); e != nil {
			return e
		}
	}
	return nil
}
func (a *SkillsBenchAdapter) state(c client.RunClaim, cursor string) (skillsbenchCursor, error) {
	if a == nil || skillsbenchConfig(c) != nil {
		return skillsbenchCursor{}, ErrSourceConfig
	}
	if cursor == "" {
		return skillsbenchCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: c.Run.ConfigVersion, Owners: map[string]skillsbenchOwner{}, Gaps: []string{"license_unverified", "official_model_identity_unknown", "skill_identities_unknown", "evaluation_time_unknown"}}, nil
	}
	var s skillsbenchCursor
	d := json.NewDecoder(strings.NewReader(cursor))
	d.DisallowUnknownFields()
	if len(cursor) > 8192 || checkedJSON([]byte(cursor)) != nil || d.Decode(&s) != nil || s.Run != c.Run.RunID || s.Source != c.Run.SourceID || s.Config != c.Run.ConfigVersion || s.Step < 1 || s.Step > 5 || !gitCommit(s.Revision) || s.Offset < 0 || s.Offset > 2000 || s.Step < 5 && s.Offset%30 != 0 || len(s.Owners) > 3 || len(s.Gaps) > 16 {
		return s, ErrSourceConfig
	}
	at, e := parseSourceTime(s.Updated)
	if e != nil || at.IsZero() {
		return s, ErrSourceConfig
	}
	for i, agent := range skillsbenchAgents {
		owner, ok := s.Owners[agent]
		if i < s.Step-1 && (!ok || !skillsbenchID(owner.ID) || !lowerHash(owner.Hash)) {
			return s, ErrSourceConfig
		}
	}
	for agent, owner := range s.Owners {
		if skillsbenchRepository(agent) == "" || !skillsbenchID(owner.ID) || !lowerHash(owner.Hash) {
			return s, ErrSourceConfig
		}
	}
	if s.Offset > 0 || s.Step == 5 {
		if !lowerHash(s.Hash) {
			return s, ErrSourceConfig
		}
	} else if s.Hash != "" {
		return s, ErrSourceConfig
	}
	for _, gap := range s.Gaps {
		switch gap {
		case "license_unverified", "official_model_identity_unknown", "skill_identities_unknown", "evaluation_time_unknown", "agent_outside_scope", "score_unknown", "partial_coverage":
		default:
			return s, ErrSourceConfig
		}
	}
	return s, nil
}
func skillsbenchID(id string) bool {
	n, e := strconv.ParseUint(id, 10, 64)
	return e == nil && n > 0 && n <= 9007199254740991 && strconv.FormatUint(n, 10) == id
}
func skillsbenchFile(revision string) string {
	return "https://huggingface.co/datasets/benchflow/skillsbench-leaderboard/raw/" + revision + "/" + skillsbenchFilePath
}
func (a *SkillsBenchAdapter) Completed(c client.RunClaim, cursor string) (bool, []string, error) {
	s, e := a.state(c, cursor)
	return s.Step == 5, append([]string(nil), s.Gaps...), e
}
func (a *SkillsBenchAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	s, e := a.state(c, cursor)
	if e != nil || s.Step == 5 {
		return fetcher.Request{}, ErrSourceConfig
	}
	link := skillsbenchEntry
	if s.Step >= 1 && s.Step <= 3 {
		link = "https://api.github.com/repos/" + skillsbenchRepository(skillsbenchAgents[s.Step-1])
	} else if s.Step == 4 {
		link = skillsbenchFile(s.Revision)
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.pending) >= 100 {
		if _, ok := a.pending[c.Run.RunID]; !ok {
			return fetcher.Request{}, ErrSourceConfig
		}
	}
	a.pending[c.Run.RunID] = skillsbenchPending{s, link}
	return fetcher.Request{SourceID: c.Run.SourceID, URL: link, AllowedHosts: append([]string(nil), c.Config.Config.AllowedHosts...), Format: fetcher.JSON, MinInterval: time.Second}, nil
}
func (a *SkillsBenchAdapter) Parse(c client.RunClaim, r fetcher.Result) (scheduler.Page, error) {
	if skillsbenchConfig(c) != nil {
		return scheduler.Page{}, ErrSourceConfig
	}
	a.mu.Lock()
	p, ok := a.pending[c.Run.RunID]
	a.mu.Unlock()
	if !ok || p.state.Source != c.Run.SourceID || p.state.Config != c.Run.ConfigVersion || r.StatusCode != 200 || r.FinalURL != p.url || checkedJSON(r.Body) != nil {
		return scheduler.Page{}, ErrSourceDocument
	}
	decoder := json.NewDecoder(strings.NewReader(string(r.Body)))
	decoder.UseNumber()
	var value any
	if decoder.Decode(&value) != nil {
		return scheduler.Page{}, ErrSourceDocument
	}
	s := p.state
	items := []scheduler.Item{}
	switch s.Step {
	case 0:
		doc, ok := value.(map[string]any)
		if !ok {
			return scheduler.Page{}, ErrSourceDocument
		}
		revision, _ := doc["sha"].(string)
		updated, _ := doc["lastModified"].(string)
		at, e := parseSourceTime(updated)
		if doc["id"] != "benchflow/skillsbench-leaderboard" || doc["gated"] != false || !gitCommit(revision) || e != nil || at.IsZero() {
			return scheduler.Page{}, ErrSourceDocument
		}
		s.Revision = revision
		s.Updated = wireTime(at)
		s.Step++
	case 1, 2, 3:
		doc, ok := value.(map[string]any)
		if !ok {
			return scheduler.Page{}, ErrSourceDocument
		}
		agent := skillsbenchAgents[s.Step-1]
		id, ok := doc["id"].(json.Number)
		if !ok || !skillsbenchID(id.String()) || doc["full_name"] != skillsbenchRepository(agent) {
			return scheduler.Page{}, ErrSourceDocument
		}
		for _, owner := range s.Owners {
			if owner.ID == id.String() {
				return scheduler.Page{}, ErrSourceDocument
			}
		}
		s.Owners[agent] = skillsbenchOwner{id.String(), rawSHA256(r.Body)}
		s.Step++
	case 4:
		rows, ok := value.([]any)
		if !ok || len(rows) > 1000 {
			return scheduler.Page{}, ErrSourceDocument
		}
		hash := rawSHA256(r.Body)
		if s.Hash != "" && s.Hash != hash {
			return scheduler.Page{}, ErrSourceDocument
		}
		s.Hash = hash
		all := []scheduler.Item{}
		seen := map[string]bool{}
		at, _ := parseSourceTime(s.Updated)
		for _, row := range rows {
			doc, ok := row.(map[string]any)
			if !ok {
				return scheduler.Page{}, ErrSourceDocument
			}
			agent, _ := doc["agent"].(string)
			owner, ok := s.Owners[agent]
			if !ok {
				s.Gaps = uniqueStrings(append(s.Gaps, "agent_outside_scope"))
				continue
			}
			for _, mode := range []string{"with-skill", "no-skill"} {
				payload, e := skillsbenchMetric(c, s, doc, mode)
				if e != nil {
					return scheduler.Page{}, e
				}
				config := payload["evaluation_config"].(map[string]any)
				params := config["parameters"].(map[string]any)
				model := params["reported_model_label"].(string)
				harness := params["harness"].(string)
				key := skillsbenchKey(owner.ID, model, harness, mode)
				if seen[key] {
					return scheduler.Page{}, ErrSourceDocument
				}
				seen[key] = true
				if payload["score"] == nil {
					s.Gaps = uniqueStrings(append(s.Gaps, "score_unknown"))
				}
				if params["coverage_status"] == "partial" {
					s.Gaps = uniqueStrings(append(s.Gaps, "partial_coverage"))
				}
				raw, e := json.Marshal(payload)
				if e != nil {
					return scheduler.Page{}, e
				}
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
				all = append(all, scheduler.Item{Kind: "metric", SourceItemID: key, EvidenceTime: at, Version: rawSHA256(canonical), EvidenceRef: skillsbenchFile(s.Revision), Payload: raw})
			}
		}
		if s.Offset > len(all) {
			return scheduler.Page{}, ErrSourceDocument
		}
		end := s.Offset + 30
		if end > len(all) {
			end = len(all)
		}
		items = all[s.Offset:end]
		s.Offset = end
		if end == len(all) {
			s.Step = 5
		}
	default:
		return scheduler.Page{}, ErrSourceConfig
	}
	cursor, e := json.Marshal(s)
	if e != nil || len(cursor) > 8192 {
		return scheduler.Page{}, ErrSourceDocument
	}
	if s.Step == 5 {
		a.mu.Lock()
		delete(a.pending, c.Run.RunID)
		a.mu.Unlock()
	}
	return scheduler.Page{Items: items, NextCursor: string(cursor), End: s.Step == 5, Gaps: append([]string(nil), s.Gaps...)}, nil
}
func skillsbenchKey(agent, model, harness, mode string) string {
	raw, _ := client.CanonicalBytes(map[string]any{"benchmark": "skillsbench", "agent": agent, "model_label": model, "harness": harness, "skill_mode": mode})
	return string(raw)
}
func skillsbenchMetric(c client.RunClaim, s skillsbenchCursor, doc map[string]any, mode string) (map[string]any, error) {
	agent, _ := doc["agent"].(string)
	owner := s.Owners[agent]
	if doc["benchmark"] != "skillsbench" || doc["version"] != "v1.1" || doc["source_release"] != "v1.1" || !skillsbenchID(owner.ID) {
		return nil, ErrSourceDocument
	}
	model, ok := doc["model"].(string)
	harness, hOK := doc["harness"].(string)
	commit, cOK := doc["source_commit"].(string)
	if !ok || model == "" || len(model) > 256 || !hOK || harness == "" || len(harness) > 256 || !cOK || !gitCommit(commit) {
		return nil, ErrSourceDocument
	}
	params := map[string]any{"reported_agent": agent, "reported_model_label": model, "official_model_identity": "unknown", "skill_identity_status": "unknown", "harness": harness, "skill_mode": mode, "source_commit": commit, "source_release": "v1.1"}
	for _, field := range []string{"coverage_status", "audit_status"} {
		v, ok := doc[field].(string)
		if !ok || len(v) > 256 || v == "" {
			return nil, ErrSourceDocument
		}
		params[field] = v
	}
	if params["coverage_status"] != "full" && params["coverage_status"] != "partial" {
		return nil, ErrSourceDocument
	}
	date, _ := doc["recomputed_date"].(string)
	if date != "" {
		params["recomputed_date"] = date
	} else {
		params["recomputed_date"] = "unknown"
	}
	if date != "" {
		if _, e := time.Parse("2006-01-02", date); e != nil {
			return nil, ErrSourceDocument
		}
	}
	for _, field := range []string{"num_tasks", "expected_trials_per_mode", "trials_per_task_target"} {
		v, ok := doc[field].(json.Number)
		if !ok || !skillsbenchCount(v.String()) {
			return nil, ErrSourceDocument
		}
		params[field] = v.String()
	}
	scoreField, trialsField := "with_skills_score", "with_selected_trials"
	if mode == "no-skill" {
		scoreField = "without_skills_score"
		trialsField = "without_selected_trials"
	}
	selected, ok := doc[trialsField].(json.Number)
	if !ok || !skillsbenchCount(selected.String()) {
		return nil, ErrSourceDocument
	}
	params["selected_trials"] = selected.String()
	if params["num_tasks"] != "87" || params["trials_per_task_target"] != "3" || params["expected_trials_per_mode"] != "261" {
		return nil, ErrSourceDocument
	}
	expected, _ := strconv.ParseUint(params["expected_trials_per_mode"].(string), 10, 64)
	trialCount, _ := strconv.ParseUint(selected.String(), 10, 64)
	if trialCount > expected || params["coverage_status"] == "full" && trialCount != expected {
		return nil, ErrSourceDocument
	}
	var score any
	if value := doc[scoreField]; value != nil {
		number, ok := value.(json.Number)
		if !ok {
			return nil, ErrSourceDocument
		}
		score = number.String()
	}
	payload := map[string]any{"schema_version": "1", "parser_version": "skillsbench-v1", "config_version": json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)), "subject": map[string]any{"category": "agent", "identity": map[string]any{"namespace": "github_repository", "key": owner.ID}}, "benchmark": "skillsbench", "benchmark_version": "v1.1", "task": "aggregate", "score": score, "unit": "percent", "scale_min": "0", "scale_max": "100", "higher_is_better": true, "evaluated_at": nil, "evaluation_scope": "configuration", "evaluation_config": map[string]any{"agent": owner.ID, "model": nil, "skills": []any{}, "parameters": params}, "provenance": "third_party", "evidence": []any{catalogEvidence(skillsbenchFile(s.Revision), s.Revision, skillsbenchFilePath, s.Hash, "benchmark"), catalogEvidence("https://api.github.com/repos/"+skillsbenchRepository(agent), nil, nil, owner.Hash, "metadata")}}
	if client.ValidateMetricPayload(payload) != nil {
		return nil, ErrSourceDocument
	}
	return payload, nil
}
func skillsbenchCount(value string) bool {
	n, e := strconv.ParseUint(value, 10, 32)
	return e == nil && n <= 10000000 && strconv.FormatUint(n, 10) == value
}
func (a *SkillsBenchAdapter) Freeze(c client.RunClaim, item scheduler.Item, at time.Time) (outbox.Record, error) {
	if skillsbenchConfig(c) != nil {
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
	id := subject["identity"].(map[string]any)["key"].(string)
	config := v["evaluation_config"].(map[string]any)
	params := config["parameters"].(map[string]any)
	agent, _ := params["reported_agent"].(string)
	model, _ := params["reported_model_label"].(string)
	harness, _ := params["harness"].(string)
	mode, _ := params["skill_mode"].(string)
	if !skillsbenchID(id) || skillsbenchRepository(agent) == "" || model == "" || harness == "" || (mode != "with-skill" && mode != "no-skill") || item.SourceItemID != skillsbenchKey(id, model, harness, mode) || subject["category"] != "agent" || v["parser_version"] != "skillsbench-v1" || v["config_version"] != json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10)) || v["benchmark"] != "skillsbench" || v["benchmark_version"] != "v1.1" || v["task"] != "aggregate" || v["unit"] != "percent" || v["higher_is_better"] != true || v["evaluated_at"] != nil || v["evaluation_scope"] != "configuration" || v["provenance"] != "third_party" || config["agent"] != id || config["model"] != nil || len(config["skills"].([]any)) != 0 || params["official_model_identity"] != "unknown" || params["skill_identity_status"] != "unknown" || params["source_release"] != "v1.1" {
		return outbox.Record{}, ErrSourceDocument
	}
	refs := v["evidence"].([]any)
	if len(refs) != 2 {
		return outbox.Record{}, ErrSourceDocument
	}
	ref := refs[0].(map[string]any)
	revision, ok := ref["revision"].(string)
	hash, hOK := ref["file_hash"].(string)
	if !ok || !gitCommit(revision) || !hOK || !lowerHash(hash) || ref["url"] != skillsbenchFile(revision) || ref["path"] != skillsbenchFilePath || ref["evidence_type"] != "benchmark" {
		return outbox.Record{}, ErrSourceDocument
	}
	ref = refs[1].(map[string]any)
	hash, hOK = ref["file_hash"].(string)
	if !hOK || !lowerHash(hash) || ref["url"] != "https://api.github.com/repos/"+skillsbenchRepository(agent) || ref["revision"] != nil || ref["path"] != nil || ref["evidence_type"] != "metadata" {
		return outbox.Record{}, ErrSourceDocument
	}
	var random [16]byte
	if _, e = rand.Read(random[:]); e != nil {
		return outbox.Record{}, e
	}
	oid := hex.EncodeToString(random[:])
	member, e := client.Hash(client.SourceItemHash, map[string]any{"source_id": c.Run.SourceID, "source_item_id": item.SourceItemID})
	if e != nil {
		return outbox.Record{}, e
	}
	row := map[string]any{"protocol_revision": "0.11", "observation_id": oid, "member_id": member, "run_id": c.Run.RunID, "business_date": c.Run.BusinessDate, "source_id": c.Run.SourceID, "source_revision": c.Run.ConfigVersion, "source_item_id": item.SourceItemID, "kind": "metric", "retrieved_at": wireTime(at), "replaces_observation_id": nil, "payload": v}
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
	key, e := client.Hash(client.SourceItemHash, map[string]any{"source_id": c.Run.SourceID, "source_item_id": oid})
	if e != nil {
		return outbox.Record{}, e
	}
	return outbox.Record{ID: oid, Kind: "observation", RecordKey: key, SourceID: c.Run.SourceID, RunID: c.Run.RunID, MemberID: member, ObservationID: oid, Payload: wire, PayloadHash: digest, ProtocolRevision: "0.11", HashVersion: 1}, nil
}
