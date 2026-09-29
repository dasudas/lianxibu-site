package config

import (
	"errors"
	"net/url"
	"strings"
)

type Config struct {
	SiteBaseURL string
	NodeID      string
	Token       string
	SQLitePath  string
}

func Load(getenv func(string) string) (Config, error) {
	cfg := Config{
		SiteBaseURL: strings.TrimSpace(getenv("SITE_BASE_URL")),
		NodeID:      strings.TrimSpace(getenv("COLLECTOR_NODE_ID")),
		Token:       getenv("COLLECTOR_TOKEN"),
		SQLitePath:  strings.TrimSpace(getenv("COLLECTOR_SQLITE_PATH")),
	}
	if cfg.SiteBaseURL == "" || cfg.NodeID == "" || cfg.Token == "" || cfg.SQLitePath == "" {
		return Config{}, errors.New("SITE_BASE_URL, COLLECTOR_NODE_ID, COLLECTOR_TOKEN and COLLECTOR_SQLITE_PATH are required")
	}
	parsed, err := url.Parse(cfg.SiteBaseURL)
	if err != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil ||
		parsed.RawQuery != "" || parsed.Fragment != "" {
		return Config{}, errors.New("SITE_BASE_URL must be a plain HTTPS origin")
	}
	if parsed.Path != "" && parsed.Path != "/" {
		return Config{}, errors.New("SITE_BASE_URL must not contain a path")
	}
	return cfg, nil
}
