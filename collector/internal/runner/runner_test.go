package runner

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/telemetry"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type monitorFixture struct{ paused bool }

func (m *monitorFixture) Tick(context.Context) (telemetry.State, error) {
	return telemetry.State{}, nil
}
func (m *monitorFixture) AllowNewInput() error {
	if m.paused {
		return telemetry.ErrNewInputPaused
	}
	return nil
}

type feedFixture struct{ calls int }

func (f *feedFixture) Fetch(context.Context, fetcher.Request) (fetcher.Result, error) {
	f.calls++
	return fetcher.Result{StatusCode: 200, ContentType: "application/atom+xml", FinalURL: "https://example.invalid/feed", Body: []byte(`<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>fictional-item</id><title>Fictional article</title><link rel="alternate" href="https://example.invalid/item"/><published>2026-09-30T01:00:00Z</published><updated>2026-09-30T01:00:00Z</updated><content type="text">Fictional article body.</content></entry></feed>`)}, nil
}
func TestDurableRuntimeWithTLSBackendAndRestart(t *testing.T) {
	ctx := context.Background()
	now := time.Date(2026, 9, 30, 8, 0, 0, 0, time.UTC)
	until := now.Add(time.Hour).Format("2006-01-02T15:04:05.000000Z")
	source := client.SourceConfig{SourceID: "cn-ruanyifeng", ConfigVersion: 1, ActivationStage: "production"}
	source.Config.Adapter = "atom"
	source.Config.EntryURLs = []string{"https://example.invalid/feed"}
	source.Config.IdentityRule = "guid"
	source.Config.WindowRule = "published_at"
	source.Config.ParserVersion = "cn-ruanyifeng-v1"
	source.Config.MaxItems = 100
	source.Config.MaxResponseBytes = 2097152
	source.Config.AllowedHosts = []string{"example.invalid"}
	source.Config.ValidUntil = until
	claim := client.RunClaim{Config: source, CurrentLeaseValid: true}
	token := "fictional-claim"
	claim.ClaimToken = &token
	claim.LeaseUntil = &until
	claim.Run.RunID = "fictional-run"
	claim.Run.SourceID = source.SourceID
	claim.Run.ConfigVersion = 1
	claim.Run.RunRevision = 1
	claim.Run.Generation = 1
	claim.Run.RunKind = "daily"
	claim.Run.BusinessDate = "2026-09-30"
	claim.Run.CollectionReason = "daily"
	claim.Run.FetchMode = "daily_delta"
	claim.Run.WindowStart = "2026-09-29T16:00:00.000000Z"
	claim.Run.WindowEnd = "2026-09-30T16:00:00.000000Z"
	revision := uint64(1)
	revoked := false
	claimed := false
	resultSeen := false
	released := false
	observationSeen := false
	loseAck := true
	var frozenObservation string
	var terminalManifest, terminalResult string
	var parts []json.RawMessage
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer fictional-node-token" {
			t.Error("node auth missing")
		}
		reply := func(data any) {
			json.NewEncoder(w).Encode(map[string]any{"data": data, "meta": map[string]any{"request_id": "fictional"}})
		}
		if revoked {
			w.WriteHeader(403)
			return
		}
		if replySourceContractSnapshot(t, w, r, now) {
			return
		}
		switch r.URL.Path {
		case "/api/collector/v1/config":
			reply(client.ConfigSnapshot{ProtocolRevision: "0.11", ValidUntil: until, Sources: []client.SourceConfig{source}})
		case "/api/collector/v1/run-claims":
			if claimed {
				reply(map[string]any{"runs": []any{}})
			} else {
				claimed = true
				reply(map[string]any{"runs": []any{claim}})
			}
		case "/api/collector/v1/runs/fictional-run":
			if resultSeen {
				reply(map[string]any{"run_id": claim.Run.RunID, "run_revision": revision, "delivery_state": "complete", "manifest_hash": terminalManifest, "result_business_hash": terminalResult, "expected_count": 1, "accepted_count": 1, "excluded_count": 0, "pending_count": 0})
			} else {
				reply(map[string]any{"run_id": claim.Run.RunID, "run_revision": revision})
			}
		case "/api/collector/v1/runs/fictional-run/manifest":
			if r.Method == "GET" {
				reply(map[string]any{"run_id": claim.Run.RunID, "parts": parts, "next_cursor": nil})
				return
			}
			raw, _ := io.ReadAll(r.Body)
			if _, e := client.DecodeManifestControl(raw, claim.Run.RunID, "fictional-node"); e != nil {
				t.Error(e)
			}
			value, _ := client.ParseStrict(raw)
			business := value.(map[string]any)["business"]
			part, _ := json.Marshal(business)
			parts = append(parts, part)
			hash, _ := client.Hash(client.ControlBusinessHash, map[string]any{"operation": "manifest", "business": business})
			revision++
			reply(client.ControlResult{RunID: &claim.Run.RunID, RunRevision: &revision, BusinessHash: hash, ReceiptRef: "fictional-manifest-receipt", CurrentLeaseValid: true, LeaseUntil: &until})
		case "/api/collector/v1/observations":
			raw, _ := io.ReadAll(r.Body)
			var body struct {
				Observations []json.RawMessage `json:"observations"`
			}
			json.Unmarshal(raw, &body)
			if len(body.Observations) != 1 {
				t.Error("wrong batch")
				w.WriteHeader(422)
				return
			}
			if frozenObservation != "" && frozenObservation != string(body.Observations[0]) {
				t.Error("replay changed observation bytes")
			}
			frozenObservation = string(body.Observations[0])
			observation, e := client.DecodeObservation(body.Observations[0])
			if e != nil {
				t.Error(e)
				w.WriteHeader(422)
				return
			}
			observationSeen = true
			if loseAck {
				loseAck = false
				w.WriteHeader(503)
				return
			}
			reply(map[string]any{"acks": []any{map[string]any{"member_id": observation.MemberID, "observation_id": observation.ID, "status": "duplicate", "receipt_ref": "fictional-observation-receipt", "accepted_at": "2026-09-30T08:00:00.000000Z"}}})
		default:
			raw, _ := io.ReadAll(r.Body)
			value, e := client.ParseStrict(raw)
			if e != nil {
				t.Error(e)
				w.WriteHeader(422)
				return
			}
			outer := value.(map[string]any)
			business := outer["business"].(map[string]any)
			operation := ""
			if replySourceContractSnapshot(t, w, r, now) {
				return
			}
			switch {
			case strings.HasSuffix(r.URL.Path, "/claim"):
				operation = "claim_" + business["action"].(string)
				released = business["action"] == "release"
			case strings.HasSuffix(r.URL.Path, "/manifest"):
				operation = "manifest"
				if _, e = client.DecodeManifestControl(raw, claim.Run.RunID, "fictional-node"); e != nil {
					t.Error(e)
				}
			case strings.HasSuffix(r.URL.Path, "/result"):
				operation = "result"
				expected := outer["attempt"].(map[string]any)["expected_run_revision"]
				if expected != json.Number(jsonNumber(revision)) {
					t.Error("result CAS not current", expected, revision)
					w.WriteHeader(409)
					return
				}
				coverage := business["coverage"].(map[string]any)
				if coverage["state"] != "partial" || len(coverage) != 4 || !observationSeen || len(coverage["missing_inputs"].([]any)) == 0 {
					t.Error("invalid result coverage")
				}
				resultSeen = true
				terminalManifest = business["manifest_hash"].(string)
			default:
				t.Error("unexpected runtime route", r.URL.Path)
				w.WriteHeader(404)
				return
			}
			hash, e := client.Hash(client.ControlBusinessHash, map[string]any{"operation": operation, "business": business})
			if operation == "result" {
				terminalResult = hash
			}
			if e != nil {
				t.Error(e)
			}
			revision++
			reply(client.ControlResult{RunID: &claim.Run.RunID, RunRevision: &revision, BusinessHash: hash, ReceiptRef: "fictional-control-receipt", CurrentLeaseValid: !released, LeaseUntil: &until})
		}
	}))
	defer server.Close()
	path := filepath.Join(t.TempDir(), "queue.db")
	queue, e := outbox.Open(path, "fictional-node", func() time.Time { return now })
	if e != nil {
		t.Fatal(e)
	}
	api, _ := client.NewClient(server.URL, "fictional-node-token", server.Client(), func() time.Time { return now })
	feed := &feedFixture{}
	monitor := &monitorFixture{}
	worker, _ := New(api, queue, feed, monitor, func() time.Time { return now })
	if e = worker.Tick(ctx); !errors.Is(e, client.ErrRetryable) {
		t.Fatal("expected lost ACK retry", e)
	}
	if feed.calls != 1 {
		t.Fatal("source was not fetched")
	}
	queue.Close()
	now = now.Add(6 * time.Second)
	queue, e = outbox.Open(path, "fictional-node", func() time.Time { return now })
	if e != nil {
		t.Fatal(e)
	}
	defer queue.Close()
	queue.Recover(ctx)
	worker, _ = New(api, queue, feed, monitor, func() time.Time { return now })
	if e = worker.Tick(ctx); e != nil {
		t.Fatal(e)
	}
	if !resultSeen || feed.calls != 1 {
		t.Fatal("completion was lost across restart")
	}
	if e = worker.Tick(ctx); e != nil {
		t.Fatal(e)
	}
	if !released {
		t.Fatal("completed lease not released")
	}
	for i := 0; i < 2; i++ {
		if e = worker.Tick(ctx); e != nil {
			t.Fatal(e)
		}
	}
	state, _, e := queue.Run(ctx, claim.Run.RunID)
	if e != nil || state.Retention == nil {
		t.Fatal("terminal proof not retained", e)
	}
	revoked = true
	if e = worker.Tick(ctx); !errors.Is(e, client.ErrPermission) {
		t.Fatal("revocation ignored", e)
	}
	if feed.calls != 1 {
		t.Fatal("revocation allowed fetch")
	}
	monitor.paused = true
	revoked = false
	if e = worker.Tick(ctx); !errors.Is(e, telemetry.ErrNewInputPaused) {
		t.Fatal(e)
	}
	cancelled, cancel := context.WithCancel(ctx)
	cancel()
	if e = worker.Run(cancelled, nil); !errors.Is(e, context.Canceled) {
		t.Fatal("shutdown ignored", e)
	}
}
func jsonNumber(value uint64) string { raw, _ := json.Marshal(value); return string(raw) }

type isolatedFailureFeed struct{ healthyCalls int }

func (f *isolatedFailureFeed) Fetch(_ context.Context, req fetcher.Request) (fetcher.Result, error) {
	if req.SourceID == "cn-ruanyifeng" {
		return fetcher.Result{}, errors.New("fictional source unavailable")
	}
	f.healthyCalls++
	return fetcher.Result{StatusCode: 200, ContentType: "application/atom+xml", FinalURL: "https://example.invalid/feed", Body: []byte(`<feed xmlns="http://www.w3.org/2005/Atom"></feed>`)}, nil
}

func TestFailedSourceDoesNotStarveAnotherClaim(t *testing.T) {
	ctx := context.Background()
	now := time.Date(2026, 9, 30, 8, 0, 0, 0, time.UTC)
	until := now.Add(time.Hour).Format("2006-01-02T15:04:05.000000Z")
	var sources []client.SourceConfig
	var claims []client.RunClaim
	for i, id := range []string{"cn-ruanyifeng", "global-simon"} {
		s := client.SourceConfig{SourceID: id, ConfigVersion: 1, ActivationStage: "production"}
		s.Config.Adapter = "atom"
		s.Config.EntryURLs = []string{"https://example.invalid/feed"}
		s.Config.IdentityRule, s.Config.WindowRule = "guid", "published_at"
		s.Config.ParserVersion, s.Config.MaxItems, s.Config.MaxResponseBytes = id+"-v1", 100, 2097152
		s.Config.AllowedHosts, s.Config.ValidUntil = []string{"example.invalid"}, until
		sources = append(sources, s)
		c := client.RunClaim{Config: s, CurrentLeaseValid: true}
		token := "fictional-claim-" + id
		c.ClaimToken, c.LeaseUntil = &token, &until
		c.Run.RunID, c.Run.SourceID = "fictional-"+jsonNumber(uint64(i)), id
		c.Run.ConfigVersion, c.Run.RunRevision, c.Run.Generation = 1, 1, 1
		c.Run.RunKind, c.Run.BusinessDate = "daily", "2026-09-30"
		c.Run.CollectionReason, c.Run.FetchMode = "daily", "daily_delta"
		c.Run.WindowStart, c.Run.WindowEnd = "2026-09-29T16:00:00.000000Z", "2026-09-30T16:00:00.000000Z"
		claims = append(claims, c)
	}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		reply := func(data any) {
			json.NewEncoder(w).Encode(map[string]any{"data": data, "meta": map[string]any{"request_id": "fictional"}})
		}
		if replySourceContractSnapshot(t, w, req, now) {
			return
		}
		switch {
		case strings.HasSuffix(req.URL.Path, "/config"):
			reply(client.ConfigSnapshot{ProtocolRevision: "0.11", ValidUntil: until, Sources: sources})
		case strings.HasSuffix(req.URL.Path, "/run-claims"):
			reply(map[string]any{"runs": claims})
		case req.Method == "GET":
			reply(map[string]any{"run_id": "fictional-1", "run_revision": 1})
		default:
			raw, _ := io.ReadAll(req.Body)
			v, err := client.ParseStrict(raw)
			if err != nil {
				t.Error(err)
				w.WriteHeader(422)
				return
			}
			business := v.(map[string]any)["business"].(map[string]any)
			operation := "result"
			if strings.HasSuffix(req.URL.Path, "/claim") {
				operation = "claim_" + business["action"].(string)
			}
			hash, err := client.Hash(client.ControlBusinessHash, map[string]any{"operation": operation, "business": business})
			if err != nil {
				t.Error(err)
			}
			id := business["run_id"].(string)
			revision := uint64(1)
			reply(client.ControlResult{RunID: &id, RunRevision: &revision, BusinessHash: hash, ReceiptRef: "fictional-receipt-" + id, CurrentLeaseValid: true, LeaseUntil: &until})
		}
	}))
	defer server.Close()
	queue, err := outbox.Open(filepath.Join(t.TempDir(), "queue.db"), "fictional-node", func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	defer queue.Close()
	api, err := client.NewClient(server.URL, "fictional-token", server.Client(), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	feed := &isolatedFailureFeed{}
	worker, err := New(api, queue, feed, &monitorFixture{}, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	if err = worker.Tick(ctx); err == nil {
		t.Fatal("source failure was lost")
	}
	if feed.healthyCalls != 1 {
		t.Fatalf("healthy source starved: calls=%d, error=%v", feed.healthyCalls, err)
	}
	state, _, err := queue.Run(ctx, "fictional-1")
	if err != nil || !state.FetchComplete {
		t.Fatal("healthy source did not complete", err)
	}
}
