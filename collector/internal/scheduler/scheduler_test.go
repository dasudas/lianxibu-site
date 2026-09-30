package scheduler

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
)

func clock() time.Time { return time.Date(2026, 9, 30, 8, 0, 0, 0, time.UTC) }
func claim(mode string) client.RunClaim {
	c := client.RunClaim{}
	c.Run.RunID = "run-production"
	c.Run.SourceID = "source"
	c.Run.ConfigVersion = 2
	c.Run.Generation = 3
	c.Run.FetchMode = mode
	c.Run.WindowStart = "2026-09-29T00:00:00.000000Z"
	c.Run.WindowEnd = "2026-09-30T00:00:00.000000Z"
	token, until := "token", "2026-09-30T09:00:00.000000Z"
	c.ClaimToken = &token
	c.LeaseUntil = &until
	c.CurrentLeaseValid = true
	return c
}

type memoryStore struct {
	state    outbox.RunState
	revision uint64
	records  []outbox.Record
}

func (m *memoryStore) ObservationCount(context.Context, string) (int, error) {
	return len(m.records), nil
}
func (m *memoryStore) HasMember(_ context.Context, _ string, id string) (bool, error) {
	for _, record := range m.records {
		if record.MemberID == id {
			return true, nil
		}
	}
	return false, nil
}

func (m *memoryStore) Run(context.Context, string) (outbox.RunState, uint64, error) {
	return m.state, m.revision, nil
}
func (m *memoryStore) SavePage(_ context.Context, id, token string, expected uint64, before, after string, records []outbox.Record) error {
	if id != "run-production" || token != "token" || expected != m.revision || before != m.state.Cursor {
		return outbox.ErrStaleRun
	}
	m.records = append(m.records, records...)
	m.state.Cursor = after
	m.revision++
	return nil
}

type memoryFetch struct {
	responses []fetcher.Result
	requests  []fetcher.Request
}

func (m *memoryFetch) Fetch(_ context.Context, r fetcher.Request) (fetcher.Result, error) {
	m.requests = append(m.requests, r)
	if len(m.responses) == 0 {
		return fetcher.Result{StatusCode: http.StatusNotModified}, nil
	}
	result := m.responses[0]
	m.responses = m.responses[1:]
	return result, nil
}

type fixtureAdapter struct{}

func (fixtureAdapter) Request(c client.RunClaim, cursor string) (fetcher.Request, error) {
	return fetcher.Request{SourceID: c.Run.SourceID, URL: "https://example.test/page?cursor=" + cursor,
		AllowedHosts: []string{"example.test"}, Format: fetcher.JSON, IfNoneMatch: "old-etag", IfModifiedSince: "old-date"}, nil
}
func (fixtureAdapter) Parse(_ client.RunClaim, result fetcher.Result) (Page, error) {
	var p Page
	err := json.Unmarshal(result.Body, &p)
	return p, err
}
func (fixtureAdapter) Freeze(c client.RunClaim, item Item, at time.Time) (outbox.Record, error) {
	return outbox.Record{ID: "obs-" + c.Run.RunID + "-" + item.SourceItemID, Kind: "observation",
		RecordKey: strings.Repeat("a", 64), SourceID: c.Run.SourceID, RunID: c.Run.RunID,
		MemberID: "member-" + item.SourceItemID, ObservationID: "obs-" + c.Run.RunID + "-" + item.SourceItemID,
		Payload: append([]byte(nil), item.Payload...), PayloadHash: strings.Repeat("b", 64), ProtocolRevision: "0.11", HashVersion: 1}, nil
}
func result(items []Item, next string, end bool) fetcher.Result {
	body, _ := json.Marshal(Page{Items: items, NextCursor: next, End: end})
	return fetcher.Result{StatusCode: http.StatusOK, Body: body}
}
func item(id, version string, when time.Time) Item {
	return Item{SourceItemID: id, Version: version, EvidenceTime: when, Payload: []byte(`{"body":"same material"}`)}
}
func setup(c client.RunClaim, maxPages int, responses ...fetcher.Result) (*Scheduler, *memoryStore, *memoryFetch) {
	store := &memoryStore{state: outbox.RunState{Frozen: c}}
	fetch := &memoryFetch{responses: responses}
	s, _ := New(fetch, store, fixtureAdapter{}, clock, maxPages, time.Minute)
	return s, store, fetch
}

func TestDailyDeltaResumesAtFrozenPageCursorAndKeepsWindow(t *testing.T) {
	c := claim("daily_delta")
	in := item("1", "v1", time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC))
	outside := item("2", "v2", time.Date(2026, 9, 30, 2, 0, 0, 0, time.UTC))
	s, store, fetch := setup(c, 1, result([]Item{in, outside}, "page-2", false), result([]Item{item("3", "v3", in.EvidenceTime)}, "", true))
	first, err := s.Run(context.Background(), c)
	if err != nil || first.Finished || first.Cursor != "page-2" || first.Observations != 1 {
		t.Fatal("page boundary", first, err)
	}
	second, err := s.Run(context.Background(), c)
	if err != nil || !second.Finished || second.Observations != 1 || store.revision != 2 || len(store.records) != 2 {
		t.Fatal("resume", second, err)
	}
	if fetch.requests[0].IfNoneMatch == "" || fetch.requests[1].URL != "https://example.test/page?cursor=page-2" {
		t.Fatal("daily validator/cursor lost")
	}
}

func TestMaterializeNeverUsesValidatorOrTrialHashShortcut(t *testing.T) {
	c := claim("materialize")
	in := item("1", "v1", time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC))
	s, store, fetch := setup(c, 3, result([]Item{in}, "", true))
	out, err := s.Run(context.Background(), c)
	if err != nil || !out.Finished || out.Observations != 1 || len(store.records) != 1 {
		t.Fatal("formal material not frozen", out, err)
	}
	if fetch.requests[0].IfNoneMatch != "" || fetch.requests[0].IfModifiedSince != "" {
		t.Fatal("materialize made conditional request")
	}
	if store.records[0].RunID != "run-production" || string(store.records[0].Payload) != string(in.Payload) {
		t.Fatal("formal evidence inherited trial bytes")
	}
}

func TestMaterialize304AndMissingHistoricalVersionRecordCoverageGap(t *testing.T) {
	c := claim("materialize")
	s, store, fetch := setup(c, 3, fetcher.Result{StatusCode: http.StatusNotModified}, fetcher.Result{StatusCode: http.StatusNotModified})
	out, err := s.Run(context.Background(), c)
	if err != nil || !out.Finished || out.Observations != 0 || len(out.CoverageGaps) != 1 || len(fetch.requests) != 2 || len(store.records) != 0 {
		t.Fatal("304 counted as material", out, err)
	}
	c.TargetVersion = json.RawMessage(`{"revision":"old-v1","updated_at":null,"evidence_ref":null}`)
	current := item("1", "new-v2", time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC))
	s, store, _ = setup(c, 3, result([]Item{current}, "", true))
	out, err = s.Run(context.Background(), c)
	if err != nil || !out.Finished || len(out.CoverageGaps) != 1 || out.CoverageGaps[0] != "target_material_unavailable" || len(store.records) != 0 {
		t.Fatal("new version impersonated old target", out, err)
	}
}

func TestFrozenModeConflictIsRejectedBeforeSourceFetch(t *testing.T) {
	c := claim("daily_delta")
	s, store, fetch := setup(c, 2)
	other := claim("materialize")
	_, err := s.Run(context.Background(), other)
	if err != ErrRunChanged || len(fetch.requests) != 0 || store.revision != 0 {
		t.Fatal("mode changed after claim", err)
	}
}

func TestResumedMaterializeDoesNotReimportMemberOrInventGap(t *testing.T) {
	c := claim("materialize")
	in := item("1", "old", time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC))
	s, store, _ := setup(c, 1, result([]Item{in}, "page-2", false), result([]Item{in}, "", true))
	first, err := s.Run(context.Background(), c)
	if err != nil || first.Observations != 1 || first.Finished {
		t.Fatal(first, err)
	}
	second, err := s.Run(context.Background(), c)
	if err != nil || !second.Finished || second.Observations != 0 || len(second.CoverageGaps) != 0 || len(store.records) != 1 {
		t.Fatal("resumed member counted as gap or duplicated", second, err)
	}
}

func TestHistoricalTargetRequiresMatchingVersionTimeAndEvidence(t *testing.T) {
	c := claim("materialize")
	c.TargetVersion = json.RawMessage(`{"revision":"old","updated_at":"2026-09-29T12:00:00.000000Z","evidence_ref":"source-version"}`)
	in := item("1", "old", time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC))
	in.VersionTime = time.Date(2026, 9, 30, 12, 0, 0, 0, time.UTC)
	in.EvidenceRef = "source-version"
	s, store, _ := setup(c, 2, result([]Item{in}, "", true))
	out, err := s.Run(context.Background(), c)
	if err != nil || len(out.CoverageGaps) != 1 || len(store.records) != 0 {
		t.Fatal("current version impersonated history", out, err)
	}
}
