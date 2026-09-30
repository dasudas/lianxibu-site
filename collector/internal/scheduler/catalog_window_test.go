package scheduler

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"testing"
	"time"
)

type catalogWindowAdapter struct{ fixtureAdapter }

func (catalogWindowAdapter) Freeze(c client.RunClaim, item Item, at time.Time) (outbox.Record, error) {
	wire, err := client.DecodeObservation(item.Payload)
	if err != nil {
		return outbox.Record{}, err
	}
	return outbox.Record{ID: wire.ID, Kind: "observation", SourceID: wire.SourceID, RunID: wire.RunID, MemberID: wire.MemberID, Payload: item.Payload}, nil
}
func catalogWindowItem(t *testing.T, kind string) Item {
	t.Helper()
	_, file, _, _ := runtime.Caller(0)
	raw, err := os.ReadFile(filepath.Join(filepath.Dir(file), "../../../contracts/wire-examples.json"))
	if err != nil {
		t.Fatal(err)
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.UseNumber()
	var examples struct {
		Cases []struct {
			Value map[string]any `json:"value"`
		} `json:"cases"`
	}
	if err = d.Decode(&examples); err != nil {
		t.Fatal(err)
	}
	c := claim("daily_delta")
	for _, example := range examples.Cases {
		row := example.Value
		if row["kind"] != kind {
			continue
		}
		row["run_id"] = c.Run.RunID
		row["source_id"] = c.Run.SourceID
		row["source_revision"] = json.Number(strconv.FormatUint(c.Run.ConfigVersion, 10))
		row["business_date"] = "2026-09-29"
		delete(row, "payload_hash")
		hash, err := client.Hash(client.ObservationHash, row)
		if err != nil {
			t.Fatal(err)
		}
		row["payload_hash"] = hash
		wire, _ := json.Marshal(row)
		if _, err = client.DecodeObservation(wire); err != nil {
			t.Fatal(err)
		}
		return Item{Kind: kind, SourceItemID: "fictional-old-object", EvidenceTime: time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC), Payload: wire}
	}
	t.Fatal("fixture absent")
	return Item{}
}
func TestCatalogAndMetricStockBypassesArticleDateFilter(t *testing.T) {
	for _, kind := range []string{"catalog", "metric"} {
		t.Run(kind, func(t *testing.T) {
			c := claim("daily_delta")
			s, store, _ := setup(c, 1, result([]Item{catalogWindowItem(t, kind)}, "", true))
			s.adapter = catalogWindowAdapter{}
			out, err := s.Run(context.Background(), c)
			if err != nil || out.Observations != 1 || len(store.records) != 1 {
				t.Fatal("old stock filtered", out, err)
			}
		})
	}
}
func TestCatalogKindMustMatchValidatedFrozenWire(t *testing.T) {
	c := claim("daily_delta")
	wrong := catalogWindowItem(t, "article")
	wrong.Kind = "catalog"
	s, store, _ := setup(c, 1, result([]Item{wrong}, "", true))
	s.adapter = catalogWindowAdapter{}
	if _, err := s.Run(context.Background(), c); err == nil || store.revision != 0 || len(store.records) != 0 {
		t.Fatal("article bypassed window as catalog")
	}
	wrong.Kind = "unknown"
	s, store, _ = setup(c, 1, result([]Item{wrong}, "", true))
	s.adapter = catalogWindowAdapter{}
	if _, err := s.Run(context.Background(), c); err == nil || store.revision != 0 {
		t.Fatal("unknown normalized kind")
	}
}
