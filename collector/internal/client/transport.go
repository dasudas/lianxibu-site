package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

var ErrPermission = errors.New("node permission denied")
var ErrRetryable = errors.New("collector server temporarily unavailable")
var ErrStaleClaim = errors.New("claim is stale")
var ErrConfigExpired = errors.New("collector configuration expired")

type SourceConfig struct {
	SourceID        string `json:"source_id"`
	ConfigVersion   uint64 `json:"config_version"`
	ActivationStage string `json:"activation_stage"`
	Config          struct {
		Adapter          string   `json:"adapter"`
		EntryURLs        []string `json:"entry_urls"`
		IdentityRule     string   `json:"identity_rule"`
		WindowRule       string   `json:"window_rule"`
		ParserVersion    string   `json:"parser_version"`
		MaxItems         uint64   `json:"max_items"`
		MaxResponseBytes uint64   `json:"max_response_bytes"`
		AllowedHosts     []string `json:"allowed_hosts"`
		ValidUntil       string   `json:"valid_until"`
		AuthSecretRef    *string  `json:"auth_secret_ref"`
	} `json:"config"`
}

type ConfigSnapshot struct {
	ProtocolRevision string         `json:"protocol_revision"`
	Sources          []SourceConfig `json:"sources"`
	NextCursor       *string        `json:"next_cursor"`
	ValidUntil       string         `json:"valid_until"`
}

func (c ConfigSnapshot) ValidAt(now time.Time) bool {
	until, err := microTime(c.ValidUntil)
	return err == nil && now.Before(until)
}

type RunClaim struct {
	Run struct {
		RunID                string          `json:"run_id"`
		SourceID             string          `json:"source_id"`
		RunKind              string          `json:"run_kind"`
		BusinessDate         string          `json:"business_date"`
		Generation           uint64          `json:"generation"`
		RunRevision          uint64          `json:"run_revision"`
		ConfigVersion        uint64          `json:"config_version"`
		FetchMode            string          `json:"fetch_mode"`
		CollectionReason     string          `json:"collection_reason"`
		WindowStart          string          `json:"window_start"`
		WindowEnd            string          `json:"window_end"`
		DeliveryState        string          `json:"delivery_state"`
		FetchState           string          `json:"fetch_state"`
		AnalysisState        string          `json:"analysis_state"`
		Coverage             json.RawMessage `json:"coverage"`
		ExpectedCount        *uint64         `json:"expected_count"`
		AcceptedCount        uint64          `json:"accepted_count"`
		ExcludedCount        uint64          `json:"excluded_count"`
		PendingCount         uint64          `json:"pending_count"`
		BarrierClosedAt      *string         `json:"barrier_closed_at"`
		LateResultsAvailable bool            `json:"late_results_available"`
		ResultBusinessHash   *string         `json:"result_business_hash"`
		ManifestHash         *string         `json:"manifest_hash"`
	} `json:"run"`
	Config            SourceConfig    `json:"config"`
	TargetVersion     json.RawMessage `json:"target_version"`
	ClaimToken        *string         `json:"claim_token"`
	LeaseUntil        *string         `json:"lease_until"`
	CurrentLeaseValid bool            `json:"current_lease_valid"`
}

type ClaimStore interface {
	// Persist the frozen run/config/token before any acknowledgement is sent.
	PersistClaim(RunClaim) error
}

type LeaseAction struct {
	RunID          string
	Kind           string
	ClaimToken     string
	IdempotencyKey string
	BusinessHash   string
	Body           []byte
}

type ControlResult struct {
	BusinessHash      string  `json:"business_hash"`
	RunID             *string `json:"run_id"`
	RunRevision       *uint64 `json:"run_revision"`
	CurrentLeaseValid bool    `json:"current_lease_valid"`
	LeaseUntil        *string `json:"lease_until"`
	ReceiptRef        string  `json:"receipt_ref"`
}

type Client struct {
	base   string
	token  string
	http   *http.Client
	now    func() time.Time
	mu     sync.RWMutex
	cached *ConfigSnapshot
}

func NewClient(baseURL, token string, transport *http.Client, now func() time.Time) (*Client, error) {
	u, err := url.Parse(baseURL)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") || token == "" || now == nil {
		return nil, errors.New("HTTPS origin, node token and clock required")
	}
	if transport == nil {
		transport = &http.Client{Timeout: 30 * time.Second}
	}
	copyClient := *transport
	copyClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if copyClient.Timeout == 0 {
		copyClient.Timeout = 30 * time.Second
	}
	return &Client{base: strings.TrimRight(baseURL, "/") + "/api/collector/v1", token: token, http: &copyClient, now: now}, nil
}

func (c *Client) Config(ctx context.Context) (ConfigSnapshot, error) {
	value, err := c.fetchConfig(ctx)
	if err == nil {
		frozen := cloneConfig(value)
		c.mu.Lock()
		c.cached = &frozen
		c.mu.Unlock()
		return cloneConfig(value), nil
	}
	if errors.Is(err, ErrPermission) {
		return ConfigSnapshot{}, err
	}
	c.mu.RLock()
	cached := c.cached
	c.mu.RUnlock()
	if cached != nil && cached.ValidAt(c.now()) {
		return cloneConfig(*cached), nil
	}
	if err != nil {
		return ConfigSnapshot{}, fmt.Errorf("%w: %v", ErrConfigExpired, err)
	}
	return ConfigSnapshot{}, ErrConfigExpired
}

func (c *Client) fetchConfig(ctx context.Context) (ConfigSnapshot, error) {
	var combined ConfigSnapshot
	seen := map[string]bool{}
	cursor := ""
	for page := 0; page < 20; page++ {
		path := "/config"
		if cursor != "" {
			path += "?cursor=" + url.QueryEscape(cursor)
		}
		raw, err := c.request(ctx, http.MethodGet, path, "", nil)
		if err != nil {
			return ConfigSnapshot{}, err
		}
		var part ConfigSnapshot
		if err := decodeData(raw, &part); err != nil {
			return ConfigSnapshot{}, err
		}
		if len(part.Sources) > 50 {
			return ConfigSnapshot{}, errors.New("oversize config page")
		}
		if page == 0 {
			combined.ProtocolRevision = part.ProtocolRevision
			combined.ValidUntil = part.ValidUntil
		}
		if part.ProtocolRevision != combined.ProtocolRevision || part.ValidUntil != combined.ValidUntil {
			return ConfigSnapshot{}, errors.New("inconsistent config pages")
		}
		for _, source := range part.Sources {
			if seen[source.SourceID] {
				return ConfigSnapshot{}, errors.New("duplicate config source")
			}
			seen[source.SourceID] = true
			combined.Sources = append(combined.Sources, source)
		}
		if part.NextCursor == nil {
			combined.NextCursor = nil
			return combined, combined.validate(c.now())
		}
		if *part.NextCursor == "" || *part.NextCursor == cursor {
			return ConfigSnapshot{}, errors.New("invalid config cursor")
		}
		cursor = *part.NextCursor
	}
	return ConfigSnapshot{}, errors.New("config pagination limit")
}

func cloneConfig(value ConfigSnapshot) ConfigSnapshot {
	raw, _ := json.Marshal(value)
	var copy ConfigSnapshot
	_ = json.Unmarshal(raw, &copy)
	return copy
}

func (c *Client) Claim(ctx context.Context, key string, sourceIDs []string, store ClaimStore) ([]RunClaim, error) {
	if store == nil || !validKey(key) {
		return nil, errors.New("stable claim identity and store required")
	}
	if _, err := c.Config(ctx); err != nil {
		return nil, err
	}
	if len(sourceIDs) > 1000 {
		return nil, errors.New("too many source IDs")
	}
	for _, id := range sourceIDs {
		if _, err := wireID(id, true); err != nil {
			return nil, err
		}
	}
	body, _ := json.Marshal(map[string]any{"source_ids": sourceIDs, "required_features": []string{}, "max_runs": 10})
	raw, err := c.request(ctx, http.MethodPost, "/run-claims", key, body)
	if err != nil {
		return nil, err
	}
	var response struct {
		Runs []RunClaim `json:"runs"`
	}
	if err := decodeData(raw, &response); err != nil {
		return nil, err
	}
	if len(response.Runs) > 10 {
		return nil, errors.New("too many run claims")
	}
	for _, claim := range response.Runs {
		if err := claim.validate(c.now()); err != nil {
			return nil, err
		}
		if err := store.PersistClaim(claim); err != nil {
			return nil, err
		}
	}
	return response.Runs, nil
}

// A fresh operation gets a new key. A network retry sends the same LeaseAction bytes and key.
func PrepareLeaseAction(kind string, claim RunClaim, key string, now time.Time) (LeaseAction, error) {
	if kind != "ack" && kind != "renew" && kind != "release" {
		return LeaseAction{}, errors.New("invalid lease action")
	}
	if !validKey(key) || !claim.CurrentLeaseValid || claim.Run.RunID == "" || claim.ClaimToken == nil || claim.LeaseUntil == nil {
		return LeaseAction{}, ErrStaleClaim
	}
	until, err := microTime(*claim.LeaseUntil)
	if err != nil || !now.Before(until) {
		return LeaseAction{}, ErrStaleClaim
	}
	business := map[string]any{"run_id": claim.Run.RunID, "action": kind}
	hash, err := Hash(ControlBusinessHash, business)
	if err != nil {
		return LeaseAction{}, err
	}
	body, err := json.Marshal(map[string]any{"business": business, "attempt": map[string]any{
		"claim_token": *claim.ClaimToken, "expected_run_revision": nil}})
	if err != nil {
		return LeaseAction{}, err
	}
	return LeaseAction{claim.Run.RunID, kind, *claim.ClaimToken, key, hash, body}, nil
}

func (c *Client) SendLeaseAction(ctx context.Context, action LeaseAction) (ControlResult, error) {
	if !validKey(action.IdempotencyKey) || action.RunID == "" || len(action.Body) == 0 {
		return ControlResult{}, errors.New("unfrozen lease action")
	}
	raw, err := c.request(ctx, http.MethodPut, "/runs/"+url.PathEscape(action.RunID)+"/claim", action.IdempotencyKey, action.Body)
	if err != nil {
		return ControlResult{}, err
	}
	var result ControlResult
	if err := decodeData(raw, &result); err != nil {
		return ControlResult{}, err
	}
	if result.BusinessHash != action.BusinessHash || result.RunID == nil || *result.RunID != action.RunID || result.ReceiptRef == "" {
		return ControlResult{}, errors.New("lease acknowledgement identity mismatch")
	}
	return result, nil
}

func (c *Client) request(ctx context.Context, method, path, key string, body []byte) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if key != "" {
		req.Header.Set("Idempotency-Key", key)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrRetryable, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return nil, ErrPermission
	}
	if resp.StatusCode == http.StatusConflict {
		return nil, ErrStaleClaim
	}
	if resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500 {
		return nil, ErrRetryable
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("collector response status %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20+1))
	if err != nil {
		return nil, err
	}
	if len(raw) > 1<<20 {
		return nil, errors.New("collector response too large")
	}
	return raw, nil
}

func decodeData(raw []byte, dest any) error {
	if _, err := ParseStrict(raw); err != nil {
		return err
	}
	var envelope struct {
		Data json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil || len(envelope.Data) == 0 {
		return errors.New("missing response data")
	}
	return json.Unmarshal(envelope.Data, dest)
}

func (c ConfigSnapshot) validate(now time.Time) error {
	if c.ProtocolRevision != "0.11" || !c.ValidAt(now) || len(c.Sources) > 1000 {
		return ErrConfigExpired
	}
	seen := map[string]bool{}
	for _, source := range c.Sources {
		if _, err := wireID(source.SourceID, true); err != nil {
			return err
		}
		if seen[source.SourceID] || source.ConfigVersion > maxSafeInteger {
			return errors.New("invalid source config")
		}
		seen[source.SourceID] = true
		if source.ActivationStage != "disabled" && source.ActivationStage != "trial" && source.ActivationStage != "production" {
			return errors.New("invalid activation stage")
		}
		until, err := microTime(source.Config.ValidUntil)
		if err != nil || !now.Before(until) {
			return ErrConfigExpired
		}
		if source.Config.Adapter == "" || source.Config.IdentityRule == "" || source.Config.WindowRule == "" ||
			source.Config.ParserVersion == "" || source.Config.MaxItems < 1 || source.Config.MaxItems > 100000 ||
			source.Config.MaxResponseBytes < 1 || source.Config.MaxResponseBytes > 10485760 ||
			len(source.Config.EntryURLs) == 0 || len(source.Config.AllowedHosts) == 0 {
			return errors.New("incomplete source config")
		}
		for _, address := range source.Config.EntryURLs {
			u, err := url.Parse(address)
			if err != nil || u.Scheme != "https" || u.User != nil || u.Hostname() == "" {
				return errors.New("unsafe source URL")
			}
		}
	}
	return nil
}

func (claim RunClaim) validate(now time.Time) error {
	if _, err := wireID(claim.Run.RunID, false); err != nil {
		return err
	}
	if _, err := wireID(claim.Run.SourceID, true); err != nil {
		return err
	}
	if claim.Run.SourceID != claim.Config.SourceID || claim.Run.ConfigVersion != claim.Config.ConfigVersion || claim.Run.RunRevision > maxSafeInteger {
		return errors.New("claim/config identity mismatch")
	}
	if claim.Run.Generation > maxSafeInteger || claim.Run.ConfigVersion > maxSafeInteger ||
		claim.Run.BusinessDate == "" || claim.Run.RunKind == "" || claim.Run.CollectionReason == "" {
		return errors.New("incomplete frozen run")
	}
	if _, err := time.Parse("2006-01-02", claim.Run.BusinessDate); err != nil {
		return err
	}
	if claim.Run.CollectionReason == "daily" && claim.Run.FetchMode != "daily_delta" {
		return errors.New("daily run mode mismatch")
	}
	if (claim.Run.CollectionReason == "historical_backfill" || claim.Run.CollectionReason == "production_qualification") && claim.Run.FetchMode != "materialize" {
		return errors.New("materialize run mode mismatch")
	}
	configUntil, err := microTime(claim.Config.Config.ValidUntil)
	if err != nil || !now.Before(configUntil) {
		return ErrConfigExpired
	}
	if claim.Run.FetchMode != "daily_delta" && claim.Run.FetchMode != "materialize" {
		return errors.New("invalid fetch mode")
	}
	start, err := microTime(claim.Run.WindowStart)
	if err != nil {
		return err
	}
	end, err := microTime(claim.Run.WindowEnd)
	if err != nil || !start.Before(end) {
		return errors.New("invalid run window")
	}
	if !claim.CurrentLeaseValid || claim.ClaimToken == nil || *claim.ClaimToken == "" || claim.LeaseUntil == nil {
		return ErrStaleClaim
	}
	until, err := microTime(*claim.LeaseUntil)
	if err != nil || !now.Before(until) {
		return ErrStaleClaim
	}
	return nil
}

func microTime(value string) (time.Time, error) {
	if !timestampPattern.MatchString(value) {
		return time.Time{}, errors.New("microsecond timestamp required")
	}
	return time.Parse("2006-01-02T15:04:05.000000Z", value)
}

func validKey(key string) bool {
	if len(key) < 1 || len(key) > 128 {
		return false
	}
	for _, c := range key {
		if c < 33 || c > 126 {
			return false
		}
	}
	return true
}
