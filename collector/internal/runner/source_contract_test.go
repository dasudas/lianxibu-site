package runner

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func replySourceContractSnapshot(t *testing.T, w http.ResponseWriter, r *http.Request, at time.Time) bool {
	t.Helper()
	if !strings.HasPrefix(r.URL.Path, "/api/collector/v1/source-contract-telemetry/") {
		return false
	}
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		t.Error(err)
		w.WriteHeader(400)
		return true
	}
	s, err := client.DecodeSourceContractTelemetry(raw)
	if err != nil {
		t.Error(err)
		w.WriteHeader(400)
		return true
	}
	if r.Method != "PUT" || !strings.HasSuffix(r.URL.Path, "/"+s.SourceID) {
		t.Error("source route")
	}
	json.NewEncoder(w).Encode(map[string]any{"data": map[string]any{"schema_version": client.SourceContractAckSchema, "source_id": s.SourceID, "config_version": s.ConfigVersion, "epoch": s.Epoch, "sequence": s.Sequence, "payload_hash": s.PayloadHash, "received_at": at.Format(client.SourceContractTime)}, "meta": map[string]any{"request_id": "fictional-source-request"}})
	return true
}

type journalFailureFetch struct {
	calls, status int
	cause         error
	before        func()
}

func (f *journalFailureFetch) Fetch(context.Context, fetcher.Request) (fetcher.Result, error) {
	f.calls++
	if f.before != nil {
		f.before()
	}
	return fetcher.Result{StatusCode: f.status, ContentType: "application/atom+xml", Body: []byte("fictional malformed source")}, f.cause
}

type journalRunnerFixture struct {
	at                time.Time
	queue             *outbox.Store
	worker            *Runner
	fetch             *journalFailureFetch
	server            *httptest.Server
	claims, telemetry int
	denyTelemetry     bool
	path              string
}

func newJournalRunnerFixture(t *testing.T) *journalRunnerFixture {
	t.Helper()
	f := &journalRunnerFixture{at: time.Date(2026, 9, 30, 8, 0, 0, 0, time.UTC), fetch: &journalFailureFetch{status: 200}, path: filepath.Join(t.TempDir(), "queue.db")}
	until := f.at.Add(time.Hour).Format(client.SourceContractTime)
	s := client.SourceConfig{SourceID: "cn-ruanyifeng", ConfigVersion: 1, ActivationStage: "production"}
	s.Config.Adapter = "atom"
	s.Config.EntryURLs = []string{"https://example.invalid/feed"}
	s.Config.IdentityRule = "guid"
	s.Config.WindowRule = "published_at"
	s.Config.ParserVersion = "cn-ruanyifeng-v1"
	s.Config.MaxItems = 100
	s.Config.MaxResponseBytes = 2097152
	s.Config.AllowedHosts = []string{"example.invalid"}
	s.Config.ValidUntil = until
	claim := client.RunClaim{Config: s, CurrentLeaseValid: true}
	token := "fictional-claim"
	claim.ClaimToken = &token
	claim.LeaseUntil = &until
	claim.Run.RunID = "fictional-journal-run"
	claim.Run.SourceID = s.SourceID
	claim.Run.ConfigVersion = 1
	claim.Run.Generation = 1
	claim.Run.RunRevision = 1
	claim.Run.RunKind = "daily"
	claim.Run.BusinessDate = "2026-09-30"
	claim.Run.CollectionReason = "daily"
	claim.Run.FetchMode = "daily_delta"
	claim.Run.WindowStart = "2026-09-29T16:00:00.000000Z"
	claim.Run.WindowEnd = "2026-09-30T16:00:00.000000Z"
	f.server = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reply := func(data any) {
			json.NewEncoder(w).Encode(map[string]any{"data": data, "meta": map[string]any{"request_id": "fictional"}})
		}
		if strings.Contains(r.URL.Path, "/source-contract-telemetry/") {
			f.telemetry++
			if f.denyTelemetry {
				w.WriteHeader(503)
				return
			}
			replySourceContractSnapshot(t, w, r, f.at)
			return
		}
		switch r.URL.Path {
		case "/api/collector/v1/config":
			reply(client.ConfigSnapshot{ProtocolRevision: "0.11", ValidUntil: until, Sources: []client.SourceConfig{s}})
		case "/api/collector/v1/run-claims":
			f.claims++
			if f.telemetry == 0 {
				t.Error("claim before native snapshot")
			}
			if f.claims == 1 {
				reply(map[string]any{"runs": []client.RunClaim{claim}})
			} else {
				reply(map[string]any{"runs": []any{}})
			}
		case "/api/collector/v1/runs/fictional-journal-run/claim":
			raw, _ := io.ReadAll(r.Body)
			v, err := client.ParseStrict(raw)
			if err != nil {
				t.Error(err)
				return
			}
			business := v.(map[string]any)["business"]
			hash, err := client.Hash(client.ControlBusinessHash, map[string]any{"operation": "claim_ack", "business": business})
			if err != nil {
				t.Error(err)
			}
			reply(client.ControlResult{RunID: &claim.Run.RunID, RunRevision: &claim.Run.RunRevision, BusinessHash: hash, ReceiptRef: "fictional-claim-ack", CurrentLeaseValid: true, LeaseUntil: &until})
		default:
			t.Error("unexpected route")
			w.WriteHeader(404)
		}
	}))
	q, err := outbox.Open(f.path, "fictional-node", func() time.Time { return f.at })
	if err != nil {
		t.Fatal(err)
	}
	f.queue = q
	api, err := client.NewClient(f.server.URL, "fictional-node-token", f.server.Client(), func() time.Time { return f.at })
	if err != nil {
		t.Fatal(err)
	}
	f.worker, err = New(api, q, f.fetch, &monitorFixture{}, func() time.Time { return f.at })
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { q.Close(); f.server.Close() })
	return f
}
func TestSourceJournalRunnerSnapshotBeforeNewClaim(t *testing.T) {
	f := newJournalRunnerFixture(t)
	f.denyTelemetry = true
	if err := f.worker.Tick(context.Background()); !errors.Is(err, client.ErrRetryable) {
		t.Fatal(err)
	}
	if f.claims != 0 || f.fetch.calls != 0 {
		t.Fatal("unknown delivery allowed new input")
	}
	f.denyTelemetry = false
	if err := f.worker.Tick(context.Background()); !errors.Is(err, scheduler.ErrUnusablePage) {
		t.Fatal("native parser format classification", err)
	}
	if f.claims != 1 || f.fetch.calls != 1 {
		t.Fatal("did not exercise actual runner")
	}
	f.at = f.at.Add(time.Minute)
	q, err := f.queue.StageSourceContractSnapshot(context.Background(), "cn-ruanyifeng", 1)
	if err != nil || q.Snapshot.TotalFailures != 1 || len(q.Snapshot.Events) != 1 {
		t.Fatal("actual parser error not durable", err)
	}
	state, revision, err := f.queue.Run(context.Background(), "fictional-journal-run")
	if err != nil || state.Cursor != "" || state.FetchComplete || revision != 0 {
		t.Fatal("parser failure advanced original page", err)
	}
}
func TestSourceJournalRunnerNetworkIsNotFormatFailure(t *testing.T) {
	for _, status := range []int{429, 503} {
		f := newJournalRunnerFixture(t)
		f.fetch.status = status
		if err := f.worker.Tick(context.Background()); err == nil || errors.Is(err, scheduler.ErrUnusablePage) {
			t.Fatal("network response classified as format", err)
		}
		f.at = f.at.Add(time.Minute)
		q, err := f.queue.StageSourceContractSnapshot(context.Background(), "cn-ruanyifeng", 1)
		if err != nil || q.Snapshot.TotalFailures != 0 {
			t.Fatal("network inflated contract failures", err)
		}
	}
}
func TestSourceJournalRunnerRetriesUnknownCompletionBeforeInput(t *testing.T) {
	f := newJournalRunnerFixture(t)
	db, err := sql.Open("sqlite", f.path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	f.fetch.before = func() {
		_, err := db.Exec(`CREATE TRIGGER IF NOT EXISTS fictional_runner_commit BEFORE UPDATE ON node_config WHEN json_extract(OLD.value,'$.in_flight')!='' AND json_extract(NEW.value,'$.in_flight')='' BEGIN SELECT RAISE(ABORT,'fictional failed completion'); END`)
		if err != nil {
			t.Error(err)
		}
	}
	if err = f.worker.Tick(context.Background()); err == nil {
		t.Fatal("completion commit should fail")
	}
	if _, err = f.queue.StageSourceContractSnapshot(context.Background(), "cn-ruanyifeng", 1); err != outbox.ErrSourceContractJournal {
		t.Fatal("unknown native completion reported healthy")
	}
	if f.fetch.calls != 1 {
		t.Fatal("missing original pending completion")
	}
	db.Exec("DROP TRIGGER fictional_runner_commit")
	f.fetch.before = nil
	f.fetch.status = 429
	f.at = f.at.Add(time.Minute)
	if err = f.worker.Tick(context.Background()); err == nil {
		t.Fatal("second actual network failure")
	}
	if f.fetch.calls != 2 {
		t.Fatal("did not recover original completion")
	}
	f.at = f.at.Add(time.Minute)
	q, err := f.queue.StageSourceContractSnapshot(context.Background(), "cn-ruanyifeng", 1)
	if err != nil || q.Snapshot.TotalFailures != 1 {
		t.Fatal("unknown completion retry lost or duplicated native fact", err)
	}
}
