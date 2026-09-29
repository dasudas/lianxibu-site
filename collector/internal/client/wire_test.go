package client

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type wireCase struct {
	Schema     string          `json:"schema"`
	Value      json.RawMessage `json:"value"`
	PathParams json.RawMessage `json:"path_params"`
	Valid      bool            `json:"valid"`
}

func loadWireCases(t *testing.T) []wireCase {
	t.Helper()
	path := filepath.Join("..", "..", "..", "contracts", "wire-examples.json")
	if directory := os.Getenv("SITE_CONTRACTS_DIR"); directory != "" {
		path = filepath.Join(directory, "wire-examples.json")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Cases []wireCase `json:"cases"`
	}
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture.Cases
}

func TestCurrentObservationWireCases(t *testing.T) {
	valid, invalid := 0, 0
	for _, sample := range loadWireCases(t) {
		if sample.Schema != "ObservationEnvelope" {
			continue
		}
		observation, err := DecodeObservation(sample.Value)
		if sample.Valid {
			if err != nil {
				t.Fatalf("valid observation rejected: %v", err)
			}
			original := append([]byte(nil), observation.FrozenWireBytes()...)
			view := observation.FrozenWireBytes()
			view[0] = '!'
			if string(observation.FrozenWireBytes()) != string(original) {
				t.Fatal("caller mutated frozen observation bytes")
			}
			if err := ValidateObservationPath(observation, "fixture"); err != nil {
				t.Fatal(err)
			}
			valid++
		} else {
			if err == nil {
				t.Fatal("invalid observation accepted")
			}
			invalid++
		}
	}
	if valid != 3 || invalid != 1 {
		t.Fatalf("case counts %d/%d", valid, invalid)
	}
}

func TestCurrentManifestControlCases(t *testing.T) {
	valid, invalid := 0, 0
	for _, sample := range loadWireCases(t) {
		if sample.Schema != "ManifestControl" {
			continue
		}
		pathRunID := "fixture"
		if string(sample.PathParams) != "null" {
			var path map[string]string
			if err := json.Unmarshal(sample.PathParams, &path); err != nil {
				t.Fatal(err)
			}
			pathRunID = path["run_id"]
		}
		control, err := DecodeManifestControl(sample.Value, pathRunID, "node-demo")
		if sample.Valid {
			if err != nil {
				t.Fatalf("valid control rejected: %v", err)
			}
			frozen := append([]byte(nil), control.FrozenBusinessJSON()...)
			view := control.FrozenBusinessJSON()
			view[0] = '!'
			if string(control.FrozenBusinessJSON()) != string(frozen) {
				t.Fatal("caller mutated frozen control business")
			}
			if control.BusinessHash != "7d3402ad1d2d76cea474094a85196222a005be440a7cfc0b613b21c1672e8676" ||
				control.AttemptHash != "10b99848bac33e51f06b65220b1572e9a3af9c5d16aa0955a9dda9ad24e01dd9" {
				t.Fatal("control hash drifted")
			}
			valid++
		} else {
			if err == nil {
				t.Fatal("invalid control accepted")
			}
			invalid++
		}
	}
	if valid != 1 || invalid != 2 {
		t.Fatalf("case counts %d/%d", valid, invalid)
	}
}

func TestCatalogMetricRejectUnknownOwnershipAndScale(t *testing.T) {
	for _, sample := range loadWireCases(t) {
		if sample.Schema != "ObservationEnvelope" || !sample.Valid {
			continue
		}
		value, err := ParseStrict(sample.Value)
		if err != nil {
			t.Fatal(err)
		}
		envelope := value.(map[string]any)
		payload := envelope["payload"].(map[string]any)
		switch envelope["kind"] {
		case "catalog":
			if err := ValidateCatalogPayload(payload); err != nil {
				t.Fatal(err)
			}
			payload["category"] = "skill"
			if err := ValidateCatalogPayload(payload); err == nil {
				t.Fatal("wrong catalog ownership accepted")
			}
		case "metric":
			if err := ValidateMetricPayload(payload); err != nil {
				t.Fatal(err)
			}
			payload["unit"] = "unknown-scale"
			if err := ValidateMetricPayload(payload); err == nil {
				t.Fatal("unknown scale accepted")
			}
			payload["unit"] = "percent"
			payload["evaluation_config"].(map[string]any)["model"] = nil
			if err := ValidateMetricPayload(payload); err == nil {
				t.Fatal("unowned configuration score accepted")
			}
		}
	}
}
