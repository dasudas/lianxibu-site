package client

import (
	"errors"
	"math/big"
	"regexp"
	"strings"
)

var decimalPattern = regexp.MustCompile(`^-?(0|[1-9][0-9]*)(\.[0-9]{1,8})?$`)

func nullableString(value any, max int) error {
	if value == nil {
		return nil
	}
	_, err := wireString(value, 0, max)
	return err
}

func validateIdentity(categoryValue, identityValue any) error {
	category, err := wireString(categoryValue, 1, 32)
	if err != nil {
		return err
	}
	identity, err := object(identityValue, "namespace", "key")
	if err != nil {
		return err
	}
	namespace, err := wireString(identity["namespace"], 1, 64)
	if err != nil {
		return err
	}
	key, err := wireString(identity["key"], 1, 4096)
	if err != nil || len([]byte(key)) > 4096 {
		return errors.New("identity key outside wire bounds")
	}
	expected := map[string]string{
		"agent": "github_repository", "workflow": "github_repository",
		"skill": "github_skill", "model": "model_registry",
	}[category]
	if expected == "" || namespace != expected {
		return errors.New("category/identity ownership mismatch")
	}
	return nil
}

func validateEvidence(value any) error {
	entries, ok := value.([]any)
	if !ok || len(entries) < 1 || len(entries) > 32 {
		return errors.New("evidence count outside bounds")
	}
	seen := make(map[string]bool)
	for _, entry := range entries {
		item, err := object(entry, "url", "revision", "path", "file_hash", "evidence_type")
		if err != nil {
			return err
		}
		if err := httpsURL(item["url"]); err != nil {
			return err
		}
		if err := nullableString(item["revision"], 256); err != nil {
			return err
		}
		if err := nullableString(item["path"], 1024); err != nil {
			return err
		}
		if item["file_hash"] != nil {
			if _, err := wireHash(item["file_hash"]); err != nil {
				return err
			}
		}
		kind, err := wireString(item["evidence_type"], 1, 32)
		if err != nil {
			return err
		}
		switch kind {
		case "metadata", "readme", "release", "skill_document", "author_report", "benchmark":
		default:
			return errors.New("unknown evidence type")
		}
		key, err := CanonicalBytes(item)
		if err != nil {
			return err
		}
		if seen[string(key)] {
			return errors.New("duplicate evidence")
		}
		seen[string(key)] = true
	}
	return nil
}

func payloadSize(value map[string]any) error {
	canonical, err := CanonicalBytes(value)
	if err != nil {
		return err
	}
	if len(canonical) > 200*1024 {
		return errors.New("payload exceeds 200 KiB")
	}
	return nil
}

func ValidateCatalogPayload(payload map[string]any) error {
	fields := []string{"schema_version", "parser_version", "config_version", "category", "identity", "title", "source_url", "version", "published_at", "updated_at", "summary", "document_text", "truncated", "evidence"}
	if _, err := object(payload, fields...); err != nil {
		return err
	}
	if payload["schema_version"] != "1" {
		return errors.New("schema_not_supported")
	}
	if _, err := wireString(payload["parser_version"], 1, 64); err != nil {
		return err
	}
	if _, err := wireInteger(payload["config_version"]); err != nil {
		return err
	}
	if err := validateIdentity(payload["category"], payload["identity"]); err != nil {
		return err
	}
	if _, err := wireString(payload["title"], 1, 512); err != nil {
		return err
	}
	if err := httpsURL(payload["source_url"]); err != nil {
		return err
	}
	if err := nullableString(payload["version"], 256); err != nil {
		return err
	}
	for _, field := range []string{"published_at", "updated_at"} {
		if err := optionalTime(payload[field]); err != nil {
			return err
		}
	}
	if err := nullableString(payload["summary"], 16384); err != nil {
		return err
	}
	if err := nullableString(payload["document_text"], 200000); err != nil {
		return err
	}
	if _, ok := payload["truncated"].(bool); !ok {
		return errors.New("truncated boolean required")
	}
	if err := validateEvidence(payload["evidence"]); err != nil {
		return err
	}
	bodyBytes := 0
	for _, field := range []string{"summary", "document_text"} {
		if text, ok := payload[field].(string); ok {
			bodyBytes += len([]byte(text))
		}
	}
	if bodyBytes > 128*1024 {
		return errors.New("catalog text exceeds 128 KiB")
	}
	return payloadSize(payload)
}

func decimal(value any) (*big.Rat, error) {
	text, ok := value.(string)
	if !ok || !decimalPattern.MatchString(text) {
		return nil, errors.New("decimal string required")
	}
	digits := strings.ReplaceAll(strings.TrimPrefix(text, "-"), ".", "")
	if len(strings.TrimLeft(digits, "0")) > 20 {
		return nil, errors.New("decimal precision exceeds 20")
	}
	number, ok := new(big.Rat).SetString(text)
	if !ok {
		return nil, errors.New("invalid decimal")
	}
	return number, nil
}

func optionalDecimal(value any) (*big.Rat, error) {
	if value == nil {
		return nil, nil
	}
	return decimal(value)
}

func ValidateMetricPayload(payload map[string]any) error {
	fields := []string{"schema_version", "parser_version", "config_version", "subject", "benchmark", "benchmark_version", "task", "score", "unit", "scale_min", "scale_max", "higher_is_better", "evaluated_at", "evaluation_scope", "evaluation_config", "provenance", "evidence"}
	if _, err := object(payload, fields...); err != nil {
		return err
	}
	if payload["schema_version"] != "1" {
		return errors.New("schema_not_supported")
	}
	if _, err := wireString(payload["parser_version"], 1, 64); err != nil {
		return err
	}
	if _, err := wireInteger(payload["config_version"]); err != nil {
		return err
	}
	subject, err := object(payload["subject"], "category", "identity")
	if err != nil {
		return err
	}
	if err := validateIdentity(subject["category"], subject["identity"]); err != nil {
		return err
	}
	for _, field := range []string{"benchmark", "benchmark_version", "task"} {
		maximum := 256
		if field == "benchmark" {
			maximum = 128
		}
		if _, err := wireString(payload[field], 1, maximum); err != nil {
			return err
		}
	}
	score, err := optionalDecimal(payload["score"])
	if err != nil {
		return err
	}
	min, err := optionalDecimal(payload["scale_min"])
	if err != nil {
		return err
	}
	max, err := optionalDecimal(payload["scale_max"])
	if err != nil {
		return err
	}
	unit, err := wireString(payload["unit"], 1, 32)
	if err != nil {
		return err
	}
	zero := big.NewRat(0, 1)
	one := big.NewRat(1, 1)
	hundred := big.NewRat(100, 1)
	switch unit {
	case "percent":
		if min == nil || max == nil || min.Cmp(zero) != 0 || max.Cmp(hundred) != 0 {
			return errors.New("percent requires 0..100")
		}
	case "ratio":
		if min == nil || max == nil || min.Cmp(zero) != 0 || max.Cmp(one) != 0 {
			return errors.New("ratio requires 0..1")
		}
	case "points":
	case "seconds":
		if score != nil && score.Cmp(zero) < 0 {
			return errors.New("negative seconds")
		}
	case "count":
		if score != nil {
			text := payload["score"].(string)
			if !validIntegerLexeme(text) {
				return errors.New("count requires nonnegative integer string")
			}
		}
	default:
		return errors.New("unknown metric scale")
	}
	if min != nil && max != nil && min.Cmp(max) > 0 {
		return errors.New("invalid metric range")
	}
	if score != nil {
		if min != nil && score.Cmp(min) < 0 {
			return errors.New("score below scale")
		}
		if max != nil && score.Cmp(max) > 0 {
			return errors.New("score above scale")
		}
	}
	if _, ok := payload["higher_is_better"].(bool); !ok {
		return errors.New("higher_is_better boolean required")
	}
	if err := optionalTime(payload["evaluated_at"]); err != nil {
		return err
	}
	scope, err := wireString(payload["evaluation_scope"], 1, 32)
	if err != nil {
		return err
	}
	if scope != "entity" && scope != "configuration" {
		return errors.New("unknown evaluation scope")
	}
	config, err := object(payload["evaluation_config"], "agent", "model", "skills", "parameters")
	if err != nil {
		return err
	}
	for _, field := range []string{"agent", "model"} {
		if err := nullableString(config[field], 256); err != nil {
			return err
		}
	}
	if scope == "configuration" {
		agent, _ := config["agent"].(string)
		model, _ := config["model"].(string)
		if agent == "" && model == "" {
			return errors.New("configuration owner required")
		}
		if subject["category"] == "skill" {
			return errors.New("configuration score cannot be skill entity score")
		}
	}
	skills, ok := config["skills"].([]any)
	if !ok || len(skills) > 100 {
		return errors.New("skills outside bounds")
	}
	seen := make(map[string]bool)
	for _, value := range skills {
		name, err := wireString(value, 1, 256)
		if err != nil || seen[name] {
			return errors.New("duplicate or invalid skill")
		}
		seen[name] = true
	}
	parameters, ok := config["parameters"].(map[string]any)
	if !ok || len(parameters) > 32 {
		return errors.New("parameters outside bounds")
	}
	for key, value := range parameters {
		if _, err := wireString(key, 1, 64); err != nil {
			return err
		}
		if _, err := wireString(value, 0, 256); err != nil {
			return err
		}
	}
	if payload["provenance"] != "author_self_report" && payload["provenance"] != "third_party" {
		return errors.New("unknown provenance")
	}
	if err := validateEvidence(payload["evidence"]); err != nil {
		return err
	}
	return payloadSize(payload)
}
