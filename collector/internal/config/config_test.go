package config

import (
	"strings"
	"testing"
)

func configured(key string) string {
	values := map[string]string{
		"SITE_BASE_URL":         "https://example.com",
		"COLLECTOR_NODE_ID":     "fixture-node",
		"COLLECTOR_TOKEN":       "fixture-token",
		"COLLECTOR_SQLITE_PATH": "fixture.sqlite",
	}
	return values[key]
}

func TestLoadAcceptsFictionalConfig(t *testing.T) {
	cfg, err := Load(configured)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.NodeID != "fixture-node" {
		t.Fatalf("unexpected node identity: %q", cfg.NodeID)
	}
}

func TestLoadRejectsMissingAndInsecureConfig(t *testing.T) {
	if _, err := Load(func(string) string { return "" }); err == nil {
		t.Fatal("missing configuration was accepted")
	}
	for _, base := range []string{"http://example.com", "https://user:pass@example.com", "https://example.com/private"} {
		_, err := Load(func(key string) string {
			if key == "SITE_BASE_URL" {
				return base
			}
			return configured(key)
		})
		if err == nil || !strings.Contains(err.Error(), "SITE_BASE_URL") {
			t.Fatalf("unsafe URL %q was accepted: %v", base, err)
		}
	}
}
