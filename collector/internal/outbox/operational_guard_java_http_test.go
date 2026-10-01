package outbox

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

type guardHTTP struct {
	status int
	code   string
}

func (g *guardHTTP) RoundTrip(r *http.Request) (*http.Response, error) {
	response, err := (submissionHTTP{}).RoundTrip(r)
	if err != nil {
		return nil, err
	}
	body, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil {
		return nil, err
	}
	response.Body = io.NopCloser(bytes.NewReader(body))
	g.status = response.StatusCode
	var envelope struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	_ = json.Unmarshal(body, &envelope)
	g.code = envelope.Error.Code
	return response, nil
}

func TestOperationalGuardJavaHTTP(t *testing.T) {
	origin := os.Getenv("V11_BASE_URL")
	if origin == "" {
		t.Skip("Java operational fixture not active")
	}
	phase, path := os.Getenv("V11_PHASE"), os.Getenv("V11_OUTBOX_PATH")
	if path == "" {
		t.Fatal("fixture outbox path required")
	}
	now := fixtureClock()
	if phase != "blocked" {
		now = now.Add(10 * time.Minute)
	}
	clock := func() time.Time { return now }
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	raw, err := os.ReadFile("../../../contracts/wire-examples.json")
	if err != nil {
		t.Fatal(err)
	}
	var examples struct {
		Cases []struct {
			Schema string         `json:"schema"`
			Valid  bool           `json:"valid"`
			Value  map[string]any `json:"value"`
		} `json:"cases"`
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err = decoder.Decode(&examples); err != nil {
		t.Fatal(err)
	}
	var value map[string]any
	for _, sample := range examples.Cases {
		if sample.Schema == "ObservationEnvelope" && sample.Valid && sample.Value["kind"] == "metric" {
			value = sample.Value
			break
		}
	}
	if value == nil {
		t.Fatal("fictional metric fixture missing")
	}
	value["observation_id"] = "v11-observation"
	value["member_id"] = "v11-member"
	value["run_id"] = "v11-run"
	value["source_id"] = "fixture:feed"
	value["source_item_id"] = "v11-fictional-item"
	value["business_date"] = "2026-01-02"
	value["source_revision"] = json.Number("1")
	payload := value["payload"].(map[string]any)
	payload["parser_version"] = "fixture-1"
	payload["config_version"] = json.Number("1")
	delete(value, "payload_hash")
	hash, err := client.Hash(client.ObservationHash, value)
	if err != nil {
		t.Fatal(err)
	}
	value["payload_hash"] = hash
	wire, err := client.CanonicalBytes(value)
	if err != nil {
		t.Fatal(err)
	}
	record := Record{ID: "v11-record", Kind: "observation", RecordKey: strings.Repeat("a", 64), SourceID: "fixture:feed", RunID: "v11-run", MemberID: "v11-member", ObservationID: "v11-observation", Payload: wire, PayloadHash: hash, ProtocolRevision: "0.11", HashVersion: 1}
	store, err := Open(path, "fixture-node", clock)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	if phase == "blocked" {
		claim := fixtureClaim()
		claim.Run.RunID = "v11-run"
		claim.Run.SourceID = "fixture:feed"
		claim.Config.SourceID = "fixture:feed"
		if err = store.PersistClaim(claim); err != nil {
			t.Fatal(err)
		}
		if err = store.SavePage(ctx, "v11-run", "token-a", 0, "", "page-1", []Record{record}); err != nil {
			t.Fatal(err)
		}
	}
	transport := &guardHTTP{}
	c, err := client.NewClient(origin, os.Getenv("V11_NODE_TOKEN"), &http.Client{Transport: transport, Timeout: 15 * time.Second}, clock)
	if err != nil {
		t.Fatal(err)
	}
	if phase == "replay" {
		// Inject loss of the local ACK state; original wire and server receipt remain immutable.
		if _, err = store.db.Exec("UPDATE outbox SET state='sending' WHERE id='v11-record'"); err != nil {
			t.Fatal(err)
		}
	}
	if err = store.Recover(ctx); err != nil {
		t.Fatal(err)
	}
	if phase == "blocked" {
		delivery, _ := client.NewDelivery(c, store)
		count, err := delivery.UploadOneBatch(ctx)
		if count != 0 || !errors.Is(err, client.ErrRetryable) || transport.status != 503 || transport.code != "capacity_guard" {
			t.Fatalf("capacity response was not retryable: %d %d %s %v", count, transport.status, transport.code, err)
		}
		store.Close()
		store, err = Open(path, "fixture-node", clock)
		if err != nil {
			t.Fatal(err)
		}
		if err = store.Recover(ctx); err != nil {
			t.Fatal(err)
		}
		got, state, err := store.Record(ctx, record.ID)
		if err != nil || state != "pending" || !bytes.Equal(got.Payload, wire) || got.PayloadHash != hash {
			t.Fatal("capacity response lost frozen pending bytes", state, err)
		}
		return
	}
	if phase == "deliver" {
		queue := &lostSubmissionAck{Store: store, fail: true}
		delivery, _ := client.NewDelivery(c, queue)
		if _, err = delivery.UploadOneBatch(ctx); err == nil || queue.fail || transport.status != 200 {
			t.Fatal("local ACK failure was not reached after recovery", err)
		}
		store.Close()
		store, err = Open(path, "fixture-node", clock)
		if err != nil {
			t.Fatal(err)
		}
		if err = store.Recover(ctx); err != nil {
			t.Fatal(err)
		}
	}
	if phase != "deliver" && phase != "replay" {
		t.Fatal("unknown fixture phase")
	}
	queue := &lostSubmissionAck{Store: store}
	delivery, _ := client.NewDelivery(c, queue)
	count, err := delivery.UploadOneBatch(ctx)
	if err != nil || count != 1 || queue.duplicates != 1 || transport.status != 200 {
		t.Fatalf("permanent ACK replay failed: %d %d %v", count, queue.duplicates, err)
	}
	got, state, err := store.Record(ctx, record.ID)
	if err != nil || state != "acknowledged" || !bytes.Equal(got.Payload, wire) || got.PayloadHash != hash {
		t.Fatal("ACK replay changed frozen wire", state, err)
	}
}
