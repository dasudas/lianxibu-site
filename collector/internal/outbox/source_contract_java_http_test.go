package outbox

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"io"
	"net/http"
	"net/url"
	"os"
	"testing"
	"time"
)

type sourceInteropHTTP struct {
	status int
	code   string
}

func (s *sourceInteropHTTP) RoundTrip(r *http.Request) (*http.Response, error) {
	response, err := (submissionHTTP{}).RoundTrip(r)
	if err != nil {
		return nil, err
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, 16385))
	response.Body.Close()
	if err != nil {
		return nil, err
	}
	response.Body = io.NopCloser(bytes.NewReader(raw))
	s.status = response.StatusCode
	s.code = ""
	var envelope struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	_ = json.Unmarshal(raw, &envelope)
	s.code = envelope.Error.Code
	return response, nil
}
func TestSourceContractJavaHTTP(t *testing.T) {
	origin := os.Getenv("VSC_BASE_URL")
	if origin == "" {
		t.Skip("actual Java fixture is not active")
	}
	u, err := url.Parse(origin)
	if err != nil || u.Scheme != "https" || u.Hostname() != "127.0.0.1" || u.Port() == "" {
		t.Fatal("fictional loopback required")
	}
	at, err := time.Parse(client.SourceContractTime, os.Getenv("VSC_NOW"))
	if err != nil {
		t.Fatal(err)
	}
	originalAt, err := time.Parse(client.SourceContractTime, os.Getenv("VSC_ORIGINAL"))
	if err != nil {
		t.Fatal(err)
	}
	id, node := os.Getenv("VSC_SOURCE"), os.Getenv("VSC_NODE")
	clock := func() time.Time { return at }
	transport := &sourceInteropHTTP{}
	api, err := client.NewClient(origin, os.Getenv("VSC_TOKEN"), &http.Client{Transport: transport, Timeout: 10 * time.Second}, clock)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	s, err := Open(os.Getenv("VSC_QUEUE"), node, clock)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	mode := os.Getenv("VSC_MODE")
	switch mode {
	case "prepare":
		for n := 0; n < 3; n++ {
			work, err := s.BeginSourceContractWork(ctx, id, 1)
			if err != nil {
				t.Fatal(err)
			}
			if err = s.CompleteSourceContractWork(ctx, id, 1, work, true); err != nil {
				t.Fatal(err)
			}
		}
		q, err := s.StageSourceContractSnapshot(ctx, id, 1)
		if err != nil || q.Snapshot.TotalFailures != 3 || len(q.Snapshot.Events) != 3 {
			t.Fatal("actual durable three events missing", err)
		}
		if err = os.WriteFile(os.Getenv("VSC_FROZEN"), q.Snapshot.FrozenWireBytes(), 0600); err != nil {
			t.Fatal(err)
		}
		ack, err := api.SubmitSourceContractSnapshot(ctx, q.Snapshot)
		if err != nil || ack.ReceivedAt != originalAt.Format(client.SourceContractTime) {
			t.Fatal("native original ACK", err)
		}
		var count int
		if err = s.db.QueryRow("SELECT COUNT(*) FROM outbox WHERE record_kind=? AND state='pending'", SourceContractRecordKind).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if count != 1 {
			t.Fatal("lost local ACK did not leave original pending")
		}
	case "replay":
		q, err := s.StageSourceContractSnapshot(ctx, id, 1)
		if err != nil {
			t.Fatal(err)
		}
		raw, err := os.ReadFile(os.Getenv("VSC_FROZEN"))
		if err != nil || !bytes.Equal(raw, q.Snapshot.FrozenWireBytes()) {
			t.Fatal("pending changed across restart", err)
		}
		ack, err := api.SubmitSourceContractSnapshot(ctx, q.Snapshot)
		if err != nil || ack.ReceivedAt != originalAt.Format(client.SourceContractTime) {
			t.Fatal("permanent ACK refreshed", err)
		}
		if err = s.ApplySourceContractAck(ctx, q, ack); err != nil {
			t.Fatal(err)
		}
		next, err := s.StageSourceContractSnapshot(ctx, id, 1)
		if err != nil || next.Snapshot.Sequence != 2 || next.Snapshot.TotalFailures != 3 || len(next.Snapshot.Events) != 0 {
			t.Fatal("actual native next window", err)
		}
		if _, err = api.SubmitSourceContractSnapshot(ctx, next.Snapshot); err == nil || transport.status != 409 || transport.code != "stale_config" {
			t.Fatal("current source config fence missing", err)
		}
		again, err := s.StageSourceContractSnapshot(ctx, id, 1)
		if err != nil || again.RecordID != next.RecordID {
			t.Fatal("failed submit consumed pending", err)
		}
	case "revoked":
		raw, err := os.ReadFile(os.Getenv("VSC_FROZEN"))
		if err != nil {
			t.Fatal(err)
		}
		old, err := client.DecodeSourceContractTelemetry(raw)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = api.SubmitSourceContractSnapshot(ctx, old); !errors.Is(err, client.ErrPermission) {
			t.Fatal("revoked credential replay accepted", err)
		}
	default:
		t.Fatal("closed fixture phase required")
	}
}
