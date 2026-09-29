package client

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"time"
	"unicode"
	"unicode/utf8"
)

var hashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
var timestampPattern = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z$`)
var datePattern = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}$`)

func object(value any, fields ...string) (map[string]any, error) {
	item, ok := value.(map[string]any)
	if !ok {
		return nil, errors.New("object required")
	}
	if len(item) != len(fields) {
		return nil, errors.New("missing or unknown object member")
	}
	for _, field := range fields {
		if _, exists := item[field]; !exists {
			return nil, fmt.Errorf("missing %s", field)
		}
	}
	return item, nil
}

func wireString(value any, min, max int) (string, error) {
	text, ok := value.(string)
	if !ok || !utf8.ValidString(text) || utf8.RuneCountInString(text) < min || utf8.RuneCountInString(text) > max {
		return "", errors.New("string outside wire bounds")
	}
	return text, nil
}

func wireID(value any, source bool) (string, error) {
	maximum := 64
	bytes := 256
	if source {
		maximum, bytes = 80, 320
	}
	text, err := wireString(value, 1, maximum)
	if err != nil || len([]byte(text)) > bytes {
		return "", errors.New("ID outside wire bounds")
	}
	for _, scalar := range text {
		if unicode.IsControl(scalar) {
			return "", errors.New("control in ID")
		}
	}
	return text, nil
}

func wireInteger(value any) (uint64, error) {
	text, ok := value.(json.Number)
	if !ok || !validIntegerLexeme(string(text)) {
		return 0, errors.New("safe integer required")
	}
	number, err := strconv.ParseUint(string(text), 10, 64)
	if err != nil || number > maxSafeInteger {
		return 0, errors.New("integer outside wire bounds")
	}
	return number, nil
}

func wireHash(value any) (string, error) {
	text, ok := value.(string)
	if !ok || !hashPattern.MatchString(text) {
		return "", errors.New("lowercase SHA-256 required")
	}
	return text, nil
}

func optionalID(value any) error {
	if value == nil {
		return nil
	}
	_, err := wireID(value, false)
	return err
}

func wireTime(value any) error {
	text, ok := value.(string)
	if !ok || !timestampPattern.MatchString(text) {
		return errors.New("UTC microsecond time required")
	}
	if _, err := time.Parse("2006-01-02T15:04:05.000000Z", text); err != nil {
		return err
	}
	return nil
}

func optionalTime(value any) error {
	if value == nil {
		return nil
	}
	return wireTime(value)
}

func wireDate(value any) error {
	text, ok := value.(string)
	if !ok || !datePattern.MatchString(text) {
		return errors.New("business date required")
	}
	if _, err := time.Parse("2006-01-02", text); err != nil {
		return err
	}
	return nil
}

func httpsURL(value any) error {
	text, err := wireString(value, 1, 2000)
	if err != nil {
		return err
	}
	parsed, err := url.Parse(text)
	if err != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil {
		return errors.New("plain HTTPS URL required")
	}
	return nil
}

type Observation struct {
	ID           string
	MemberID     string
	RunID        string
	SourceID     string
	SourceItemID string
	Kind         string
	PayloadHash  string
	wireBytes    []byte
}

func DecodeObservation(raw []byte) (Observation, error) {
	value, err := ParseStrict(raw)
	if err != nil {
		return Observation{}, err
	}
	observation, err := validateObservation(value)
	if err != nil {
		return Observation{}, err
	}
	observation.wireBytes = append([]byte(nil), raw...)
	return observation, nil
}

func (observation Observation) FrozenWireBytes() []byte {
	return append([]byte(nil), observation.wireBytes...)
}

func validateObservation(value any) (Observation, error) {
	fields := []string{"protocol_revision", "observation_id", "member_id", "run_id", "business_date", "source_id", "source_revision", "source_item_id", "kind", "retrieved_at", "replaces_observation_id", "payload", "payload_hash"}
	envelope, err := object(value, fields...)
	if err != nil {
		return Observation{}, err
	}
	if envelope["protocol_revision"] != "0.11" {
		return Observation{}, errors.New("schema_not_supported")
	}
	id, err := wireID(envelope["observation_id"], false)
	if err != nil {
		return Observation{}, err
	}
	memberID, err := wireID(envelope["member_id"], false)
	if err != nil {
		return Observation{}, err
	}
	runID, err := wireID(envelope["run_id"], false)
	if err != nil {
		return Observation{}, err
	}
	sourceID, err := wireID(envelope["source_id"], true)
	if err != nil {
		return Observation{}, err
	}
	sourceItemID, err := wireString(envelope["source_item_id"], 1, 4096)
	if err != nil {
		return Observation{}, err
	}
	if err := wireDate(envelope["business_date"]); err != nil {
		return Observation{}, err
	}
	if _, err := wireInteger(envelope["source_revision"]); err != nil {
		return Observation{}, err
	}
	if err := wireTime(envelope["retrieved_at"]); err != nil {
		return Observation{}, err
	}
	if err := optionalID(envelope["replaces_observation_id"]); err != nil {
		return Observation{}, err
	}
	kind, ok := envelope["kind"].(string)
	if !ok {
		return Observation{}, errors.New("kind required")
	}
	payload, ok := envelope["payload"].(map[string]any)
	if !ok {
		return Observation{}, errors.New("payload object required")
	}
	switch kind {
	case "article":
		err = validateArticlePayload(payload)
	case "catalog":
		err = ValidateCatalogPayload(payload)
	case "metric":
		err = ValidateMetricPayload(payload)
	default:
		err = errors.New("schema_not_supported")
	}
	if err != nil {
		return Observation{}, err
	}
	hash, err := wireHash(envelope["payload_hash"])
	if err != nil {
		return Observation{}, err
	}
	unhashed := make(map[string]any, len(envelope)-1)
	for key, item := range envelope {
		if key != "payload_hash" {
			unhashed[key] = item
		}
	}
	calculated, err := Hash(ObservationHash, unhashed)
	if err != nil || calculated != hash {
		return Observation{}, errors.New("observation hash mismatch")
	}
	return Observation{ID: id, MemberID: memberID, RunID: runID, SourceID: sourceID,
		SourceItemID: sourceItemID, Kind: kind, PayloadHash: hash}, nil
}

func validateArticlePayload(payload map[string]any) error {
	fields := []string{"title", "source_url", "published_at", "updated_at", "time_basis", "source_summary", "body_text", "content_status", "body_truncated", "parser_version", "config_version", "source_order"}
	if _, err := object(payload, fields...); err != nil {
		return err
	}
	if _, err := wireString(payload["title"], 1, 512); err != nil {
		return err
	}
	if err := httpsURL(payload["source_url"]); err != nil {
		return err
	}
	for _, field := range []string{"published_at", "updated_at"} {
		if err := optionalTime(payload[field]); err != nil {
			return err
		}
	}
	for _, field := range []string{"source_summary", "body_text"} {
		if payload[field] != nil {
			if _, err := wireString(payload[field], 0, 200000); err != nil {
				return err
			}
		}
	}
	if _, ok := payload["body_truncated"].(bool); !ok {
		return errors.New("body_truncated boolean required")
	}
	if _, err := wireString(payload["time_basis"], 1, 32); err != nil {
		return err
	}
	if _, err := wireString(payload["content_status"], 1, 32); err != nil {
		return err
	}
	if _, err := wireString(payload["parser_version"], 1, 64); err != nil {
		return err
	}
	if _, err := wireInteger(payload["config_version"]); err != nil {
		return err
	}
	order, err := object(payload["source_order"], "basis", "domain", "value", "evidence_ref")
	if err != nil {
		return err
	}
	basis, err := wireString(order["basis"], 1, 32)
	if err != nil {
		return err
	}
	if basis == "unknown" {
		for _, field := range []string{"domain", "value", "evidence_ref"} {
			if order[field] != nil {
				return errors.New("unknown source order requires null evidence")
			}
		}
	} else if basis != "sequence" && basis != "revision_time" {
		return errors.New("unknown source order basis")
	}
	return nil
}

type ManifestControl struct {
	RunID        string
	PartNo       uint64
	PartHash     string
	BusinessHash string
	AttemptHash  string
	businessJSON []byte
	attemptJSON  []byte
}

func DecodeManifestControl(raw []byte, pathRunID, nodeID string) (ManifestControl, error) {
	value, err := ParseStrict(raw)
	if err != nil {
		return ManifestControl{}, err
	}
	input, err := object(value, "business", "attempt")
	if err != nil {
		return ManifestControl{}, err
	}
	business, err := object(input["business"], "run_id", "part_no", "part_hash", "members")
	if err != nil {
		return ManifestControl{}, err
	}
	runID, err := wireID(business["run_id"], false)
	if err != nil || runID != pathRunID {
		return ManifestControl{}, errors.New("target_mismatch")
	}
	partNo, err := wireInteger(business["part_no"])
	if err != nil {
		return ManifestControl{}, err
	}
	partHash, err := wireHash(business["part_hash"])
	if err != nil {
		return ManifestControl{}, err
	}
	members, ok := business["members"].([]any)
	if !ok || len(members) > 100 {
		return ManifestControl{}, errors.New("manifest member limit")
	}
	sorted := make([]any, len(members))
	copy(sorted, members)
	seen := make(map[string]bool)
	for _, member := range sorted {
		item, err := object(member, "member_id", "source_item_id", "observation_id", "payload_hash", "replaces_observation_id")
		if err != nil {
			return ManifestControl{}, err
		}
		memberID, err := wireID(item["member_id"], false)
		if err != nil || seen[memberID] {
			return ManifestControl{}, errors.New("duplicate or invalid member ID")
		}
		seen[memberID] = true
		if item["source_item_id"] != nil {
			if _, err := wireString(item["source_item_id"], 1, 4096); err != nil {
				return ManifestControl{}, err
			}
		}
		for _, field := range []string{"observation_id", "replaces_observation_id"} {
			if err := optionalID(item[field]); err != nil {
				return ManifestControl{}, err
			}
		}
		if item["payload_hash"] != nil {
			if _, err := wireHash(item["payload_hash"]); err != nil {
				return ManifestControl{}, err
			}
		}
	}
	sort.Slice(sorted, func(left, right int) bool {
		return sorted[left].(map[string]any)["member_id"].(string) < sorted[right].(map[string]any)["member_id"].(string)
	})
	part := map[string]any{"run_id": runID, "part_no": business["part_no"], "members": sorted}
	canonical, err := CanonicalBytes(part)
	if err != nil || len(canonical) > 128*1024 {
		return ManifestControl{}, errors.New("manifest part size")
	}
	calculated, err := Hash(ManifestPartHash, part)
	if err != nil || calculated != partHash {
		return ManifestControl{}, errors.New("manifest part hash mismatch")
	}
	attempt, err := object(input["attempt"], "claim_token", "expected_run_revision")
	if err != nil {
		return ManifestControl{}, err
	}
	if _, err := wireID(attempt["claim_token"], false); err != nil {
		return ManifestControl{}, err
	}
	if attempt["expected_run_revision"] != nil {
		if _, err := wireInteger(attempt["expected_run_revision"]); err != nil {
			return ManifestControl{}, err
		}
	}
	if _, err := wireID(nodeID, false); err != nil {
		return ManifestControl{}, err
	}
	normalBusiness := map[string]any{"run_id": runID, "part_no": business["part_no"], "part_hash": partHash, "members": sorted}
	businessHash, err := Hash(ControlBusinessHash, map[string]any{"operation": "manifest", "business": normalBusiness})
	if err != nil {
		return ManifestControl{}, err
	}
	attemptHash, err := Hash(ControlAttemptHash, map[string]any{"node_id": nodeID, "operation": "manifest", "business_hash": businessHash, "attempt": attempt})
	if err != nil {
		return ManifestControl{}, err
	}
	businessJSON, err := CanonicalBytes(normalBusiness)
	if err != nil {
		return ManifestControl{}, err
	}
	attemptJSON, err := CanonicalBytes(attempt)
	if err != nil {
		return ManifestControl{}, err
	}
	return ManifestControl{RunID: runID, PartNo: partNo, PartHash: partHash,
		BusinessHash: businessHash, AttemptHash: attemptHash,
		businessJSON: businessJSON, attemptJSON: attemptJSON}, nil
}

func (control ManifestControl) FrozenBusinessJSON() []byte {
	return append([]byte(nil), control.businessJSON...)
}

func (control ManifestControl) FrozenAttemptJSON() []byte {
	return append([]byte(nil), control.attemptJSON...)
}

// ValidateObservationPath keeps URL identity separate from untrusted body identity.
func ValidateObservationPath(observation Observation, pathRunID string) error {
	if observation.RunID != pathRunID {
		return errors.New("target_mismatch")
	}
	return nil
}
