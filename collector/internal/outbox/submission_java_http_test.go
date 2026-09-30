package outbox

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

type submissionHTTP struct{}

func (submissionHTTP) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.URL.Hostname() != "127.0.0.1" {
		return nil, errors.New("fixture only permits loopback")
	}
	copyRequest := r.Clone(r.Context())
	copyURL := *r.URL
	copyURL.Scheme = "http"
	copyRequest.URL = &copyURL
	return http.DefaultTransport.RoundTrip(copyRequest)
}

type lostSubmissionAck struct {
	*Store
	fail       bool
	duplicates int
}

func (q *lostSubmissionAck) ApplyObservationAcks(ctx context.Context, b []client.FrozenObservation, a []client.ObservationAck) error {
	if q.fail {
		q.fail = false
		return errors.New("fixture lost local ACK before commit")
	}
	for _, ack := range a {
		if ack.Status == "duplicate" {
			q.duplicates++
		}
	}
	return q.Store.ApplyObservationAcks(ctx, b, a)
}

func TestSubmissionTimeJavaHTTP(t *testing.T) {
	origin := os.Getenv("V10_BASE_URL")
	if origin == "" {
		t.Skip("Java fixture not active")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	c, err := client.NewClient(origin, os.Getenv("V10_NODE_TOKEN"), &http.Client{Transport: submissionHTTP{}, Timeout: 15 * time.Second}, fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "real-wire.db")
	store, err := Open(path, "fixture-node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	var records []Record
	for _, source := range []string{"fixture:feed", "global-hn"} {
		claim := fixtureClaim()
		claim.Run.RunID = "v10-" + source
		claim.Run.SourceID = source
		claim.Config.SourceID = source
		if err = store.PersistClaim(claim); err != nil {
			t.Fatal(err)
		}
	}
	for index, id := range []string{"old", "hn", "unicode", "outside"} {
		source, protocol, basis := "global-hn", "0.12", "submitted"
		if id == "old" {
			source, protocol, basis = "fixture:feed", "0.11", "updated"
		}
		submitted := "2026-01-02T00:00:00.000000Z"
		if id == "outside" {
			submitted = "2026-01-02T16:00:00.000000Z"
		}
		title := "Fictional " + id
		body := "Fictional body"
		if id == "unicode" {
			title = strings.Repeat("😀", 512)
			body = strings.Repeat("😀", 50000)
		}
		payload := map[string]any{"title": title, "source_url": "https://example.invalid/" + id,
			"published_at": nil, "updated_at": nil, "time_basis": basis, "source_summary": nil,
			"body_text": body, "content_status": "full", "body_truncated": false, "parser_version": "fixture-parser",
			"config_version": json.Number("1"), "source_order": map[string]any{"basis": "unknown", "domain": nil, "value": nil, "evidence_ref": nil}}
		if protocol == "0.12" {
			payload["submitted_at"] = submitted
		} else {
			payload["published_at"] = "2026-01-02T00:00:00.000000Z"
			payload["updated_at"] = "2026-01-02T01:00:00.000000Z"
		}
		value := map[string]any{"protocol_revision": protocol, "observation_id": "v10-" + id, "member_id": "member-" + id,
			"run_id": "v10-" + source, "business_date": "2026-01-02", "source_id": source, "source_revision": json.Number("1"),
			"source_item_id": "fictional-" + id, "kind": "article", "retrieved_at": "2026-01-02T02:00:00.000000Z",
			"replaces_observation_id": nil, "payload": payload}
		hash, err := client.Hash(client.ObservationHash, value)
		if err != nil {
			t.Fatal(err)
		}
		value["payload_hash"] = hash
		wire, err := client.CanonicalBytes(value)
		if err != nil {
			t.Fatal(err)
		}
		decoded, err := client.DecodeObservation(wire)
		if err != nil {
			t.Fatal(err)
		}
		record := Record{ID: "record-" + id, Kind: "observation", RecordKey: strings.Repeat(string(rune('1'+index)), 64),
			SourceID: source, RunID: decoded.RunID, MemberID: decoded.MemberID, ObservationID: decoded.ID, Payload: wire,
			PayloadHash: hash, ProtocolRevision: protocol, HashVersion: 1}
		records = append(records, record)
	}
	if err = store.SavePage(ctx, "v10-fixture:feed", "token-a", 0, "", "page-1", records[:1]); err != nil {
		t.Fatal(err)
	}
	if err = store.SavePage(ctx, "v10-global-hn", "token-a", 0, "", "page-1", records[1:]); err != nil {
		t.Fatal(err)
	}
	store.Close()
	store, err = Open(path, "fixture-node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	for _, record := range records {
		got, _, e := store.Record(ctx, record.ID)
		if e != nil || !bytes.Equal(record.Payload, got.Payload) || record.PayloadHash != got.PayloadHash {
			t.Fatal("real wire changed after restart", e)
		}
	}
	q := &lostSubmissionAck{Store: store, fail: true}
	delivery, _ := client.NewDelivery(c, q)
	if _, err = delivery.UploadOneBatch(ctx); err == nil || q.fail {
		t.Fatal("local ACK failure was not reached", err)
	}
	store.Close()
	store, err = Open(path, "fixture-node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	if err = store.Recover(ctx); err != nil {
		t.Fatal(err)
	}
	q = &lostSubmissionAck{Store: store}
	delivery, _ = client.NewDelivery(c, q)
	if count, err := delivery.UploadOneBatch(ctx); err != nil || count != 4 || q.duplicates != 4 {
		t.Fatalf("frozen replay did not recover all duplicate ACKs: %d %d %v", count, q.duplicates, err)
	}
	for _, record := range records {
		got, state, e := store.Record(ctx, record.ID)
		if e != nil || state != "acknowledged" || !bytes.Equal(got.Payload, record.Payload) || got.PayloadHash != record.PayloadHash {
			t.Fatal("ACK modified frozen wire", e)
		}
	}
}
