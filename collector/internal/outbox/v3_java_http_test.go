package outbox

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

type v3LeaseAckQueue struct {
	*Store
	lost     bool
	observed []client.ControlResult
}

func (q *v3LeaseAckQueue) ApplyControlReceipt(ctx context.Context, a client.ControlAttempt, r client.ControlResult) (bool, error) {
	q.observed = append(q.observed, r)
	if q.lost {
		q.lost = false
		return false, errors.New("fictional lost lease ACK before local commit")
	}
	return q.Store.ApplyControlReceipt(ctx, a, r)
}
func v3ClaimAndAck(t *testing.T, c *client.Client, s *Store, prefix string) {
	t.Helper()
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		if _, e := c.Claim(ctx, prefix+string(rune('a'+i)), []string{"fixture:go"}, s); e != nil {
			t.Fatal("real Java claim failed", e)
		}
	}
	d, e := client.NewDelivery(c, s)
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 4; i++ {
		ok, e := d.DeliverOneControl(ctx)
		if e != nil {
			t.Fatal("real Java claim ACK failed", e)
		}
		if !ok {
			return
		}
	}
	t.Fatal("fictional claim ACKs did not drain")
}
func v3StoredClaim(t *testing.T, s *Store, run string) client.RunClaim {
	t.Helper()
	state, _, e := s.Run(context.Background(), run)
	if e != nil {
		t.Fatal(e)
	}
	var token []byte
	var until int64
	if e = s.db.QueryRow("SELECT claim_token,lease_until FROM source_state WHERE record_kind='run' AND state_key=?", run).Scan(&token, &until); e != nil {
		t.Fatal(e)
	}
	c := state.Frozen
	tok, at := string(token), time.UnixMicro(until).UTC().Format("2006-01-02T15:04:05.000000Z")
	c.ClaimToken = &tok
	c.LeaseUntil = &at
	c.CurrentLeaseValid = true
	return c
}
func v3FrozenMetric(t *testing.T, claim client.RunClaim, now time.Time) Record {
	t.Helper()
	raw, e := os.ReadFile("../../../contracts/wire-examples.json")
	if e != nil {
		t.Fatal(e)
	}
	value, e := client.ParseStrict(raw)
	if e != nil {
		t.Fatal(e)
	}
	var wire map[string]any
	for _, entry := range value.(map[string]any)["cases"].([]any) {
		sample := entry.(map[string]any)
		v, ok := sample["value"].(map[string]any)
		if ok && sample["schema"] == "ObservationEnvelope" && sample["valid"] == true && v["kind"] == "metric" {
			wire = v
			break
		}
	}
	if wire == nil {
		t.Fatal("public fictional metric fixture missing")
	}
	wire["observation_id"] = "v04-observation"
	wire["member_id"] = "v04-member"
	wire["run_id"] = claim.Run.RunID
	wire["business_date"] = claim.Run.BusinessDate
	wire["source_id"] = "fixture:go"
	wire["source_revision"] = claim.Run.ConfigVersion
	wire["source_item_id"] = "fictional-v04-metric"
	wire["retrieved_at"] = now.UTC().Format("2006-01-02T15:04:05.000000Z")
	wire["replaces_observation_id"] = nil
	payload := wire["payload"].(map[string]any)
	payload["parser_version"] = claim.Config.Config.ParserVersion
	payload["config_version"] = claim.Config.ConfigVersion
	delete(wire, "payload_hash")
	hash, e := client.Hash(client.ObservationHash, wire)
	if e != nil {
		t.Fatal(e)
	}
	wire["payload_hash"] = hash
	body, e := client.CanonicalBytes(wire)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = client.DecodeObservation(body); e != nil {
		t.Fatal("fictional metric must satisfy wire contract", e)
	}
	return Record{ID: "v04-observation", Kind: "observation", RecordKey: strings.Repeat("d", 64), SourceID: "fixture:go", RunID: claim.Run.RunID, MemberID: "v04-member", ObservationID: "v04-observation", Payload: body, PayloadHash: hash, ProtocolRevision: wire["protocol_revision"].(string), HashVersion: 1}
}

func TestV3SQLiteJavaHTTP(t *testing.T) {
	origin := os.Getenv("V04_BASE_URL")
	if origin == "" {
		t.Skip("real Java/MySQL fictional fixture is opt-in")
	}
	u, e := url.Parse(origin)
	if e != nil || u.Scheme != "https" || u.Hostname() != "127.0.0.1" || u.Port() == "" {
		t.Fatal("Java fixture must be loopback")
	}
	path := os.Getenv("V04_QUEUE_PATH")
	if !filepath.IsAbs(path) || filepath.Base(path) != "queue.db" || !strings.HasPrefix(filepath.Base(filepath.Dir(path)), "fictional-v04-") {
		t.Fatal("owned fictional queue required")
	}
	now, e := time.Parse(time.RFC3339Nano, os.Getenv("V04_NOW"))
	if e != nil {
		t.Fatal(e)
	}
	clock := func() time.Time { return now }
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	c, e := client.NewClient(origin, os.Getenv("V04_NODE_TOKEN"), &http.Client{Transport: submissionHTTP{}, Timeout: 10 * time.Second}, clock)
	if e != nil {
		t.Fatal(e)
	}
	mode := os.Getenv("V04_MODE")
	if mode == "fresh" {
		if _, e = os.Stat(path); !errors.Is(e, os.ErrNotExist) {
			t.Fatal("fresh test must own an empty new queue")
		}
	}
	s, e := Open(path, "fixture-node", clock)
	if e != nil {
		t.Fatal(e)
	}
	defer func() { s.Close() }()
	if mode == "fresh" {
		v3ClaimAndAck(t, c, s, "v04-fresh-")
		claim := v3StoredClaim(t, s, "v04-renew")
		r := v3FrozenMetric(t, claim, now)
		if e = s.SavePage(ctx, "v04-renew", *claim.ClaimToken, 0, "", "end", []Record{r}); e != nil {
			t.Fatal(e)
		}
		backup := filepath.Join(filepath.Dir(path), "consistent-backup.db")
		if _, e = s.db.Exec("VACUUM INTO ?", backup); e != nil {
			t.Fatal(e)
		}
		d, e := client.NewDelivery(c, s)
		if e != nil {
			t.Fatal(e)
		}
		if n, e := d.UploadOneBatch(ctx); e != nil || n != 1 {
			t.Fatal("fresh metric rejected", n, e)
		}
		if e = s.Close(); e != nil {
			t.Fatal(e)
		}
		// VACUUM INTO is an immutable consistent copy; both original handles are
		// closed before restoring it. Never copy a live WAL main file alone.
		data, e := os.ReadFile(backup)
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(path, data, 0600); e != nil {
			t.Fatal(e)
		}
		s, e = Open(path, "fixture-node", clock)
		if e != nil {
			t.Fatal(e)
		}
		if e = s.Recover(ctx); e != nil {
			t.Fatal(e)
		}
		q := &lostSubmissionAck{Store: s}
		d, e = client.NewDelivery(c, q)
		if e != nil {
			t.Fatal(e)
		}
		if n, e := d.UploadOneBatch(ctx); e != nil || n != 1 || q.duplicates != 1 {
			t.Fatal("real Java backup replay not duplicate", n, e)
		}
		got, state, e := s.Record(ctx, r.ID)
		if e != nil || state != "acknowledged" || got.PayloadHash != r.PayloadHash || !bytes.Equal(got.Payload, r.Payload) {
			t.Fatal("restored observation changed", e)
		}
		if _, e = s.StageLeaseAction(ctx, "v04-renew", *claim.ClaimToken, "renew"); e != nil {
			t.Fatal(e)
		}
		d, e = client.NewDelivery(c, s)
		if e != nil {
			t.Fatal(e)
		}
		if ok, e := d.DeliverOneControl(ctx); !ok || e != nil {
			t.Fatal("renew A rejected", e)
		}
		return
	}
	if mode == "lost-b" {
		claim := v3StoredClaim(t, s, "v04-renew")
		b, e := s.StageLeaseAction(ctx, "v04-renew", *claim.ClaimToken, "renew")
		if e != nil {
			t.Fatal(e)
		}
		q := &v3LeaseAckQueue{Store: s, lost: true}
		d, e := client.NewDelivery(c, q)
		if e != nil {
			t.Fatal(e)
		}
		if ok, e := d.DeliverOneControl(ctx); ok || e == nil || len(q.observed) != 1 {
			t.Fatal("B ACK loss not injected")
		}
		first := q.observed[0]
		retry, e := s.StageLeaseAction(ctx, "v04-renew", *claim.ClaimToken, "renew")
		if e != nil || retry.ID != b.ID || retry.IdempotencyKey != b.IdempotencyKey || !bytes.Equal(retry.Wire, b.Wire) {
			t.Fatal("lost B created a new period", e)
		}
		if e = s.Close(); e != nil {
			t.Fatal(e)
		}
		s, e = Open(path, "fixture-node", clock)
		if e != nil {
			t.Fatal(e)
		}
		if e = s.Recover(ctx); e != nil {
			t.Fatal(e)
		}
		q = &v3LeaseAckQueue{Store: s}
		d, e = client.NewDelivery(c, q)
		if e != nil {
			t.Fatal(e)
		}
		if ok, e := d.DeliverOneControl(ctx); !ok || e != nil || len(q.observed) != 1 {
			t.Fatal("B restart replay failed", e)
		}
		second := q.observed[0]
		if first.ReceiptRef != second.ReceiptRef || first.LeaseUntil == nil || second.LeaseUntil == nil || *first.LeaseUntil != *second.LeaseUntil {
			t.Fatal("B replay changed original committed result")
		}
		for _, step := range []struct{ run, kind string }{{"v04-renew", "renew"}, {"v04-release", "release"}} {
			claim := v3StoredClaim(t, s, step.run)
			if _, e = s.StageLeaseAction(ctx, step.run, *claim.ClaimToken, step.kind); e != nil {
				t.Fatal(e)
			}
		}
		for i := 0; i < 2; i++ {
			if a, e := s.NextControlAttempt(ctx); e != nil || a == nil {
				t.Fatal("pending C/release missing", e)
			}
		}
		return
	}
	if mode == "takeover" {
		if e = s.Recover(ctx); e != nil {
			t.Fatal(e)
		}
		old := []client.ControlAttempt{}
		for i := 0; i < 2; i++ {
			a, e := s.NextControlAttempt(ctx)
			if e != nil || a == nil {
				t.Fatal("old attempts missing", e)
			}
			old = append(old, *a)
		}
		v3ClaimAndAck(t, c, s, "v04-takeover-")
		for _, a := range old {
			before := v3StoredClaim(t, s, a.RunID)
			_, e := c.SendLeaseAction(ctx, client.LeaseAction{RunID: a.RunID, Kind: "renew", IdempotencyKey: a.IdempotencyKey, BusinessHash: a.BusinessHash, Body: a.Wire})
			if !errors.Is(e, client.ErrStaleClaim) {
				t.Fatal("real Java old action did not reject", e)
			}
			after := v3StoredClaim(t, s, a.RunID)
			if *before.ClaimToken != *after.ClaimToken || *before.LeaseUntil != *after.LeaseUntil {
				t.Fatal("old action changed new local lease")
			}
			for _, id := range []string{a.ID, a.BusinessID} {
				_, state, e := s.Record(ctx, id)
				if e != nil || state != "superseded" {
					t.Fatal("old frozen evidence lost", e)
				}
			}
		}
		got, state, e := s.Record(ctx, "v04-observation")
		if e != nil || state != "acknowledged" || got.ObservationID != "v04-observation" {
			t.Fatal("new claims rewrote old observation", e)
		}
		return
	}
	t.Fatal("unknown controlled v3 fixture phase")
}
