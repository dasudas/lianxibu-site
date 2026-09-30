package scheduler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
)

var ErrRunChanged = errors.New("frozen run changed")
var ErrUnusablePage = errors.New("source page could not be normalized")

type Fetcher interface {
	Fetch(context.Context, fetcher.Request) (fetcher.Result, error)
}
type Store interface {
	Run(context.Context, string) (outbox.RunState, uint64, error)
	ObservationCount(context.Context, string) (int, error)
	HasMember(context.Context, string, string) (bool, error)
	SavePage(context.Context, string, string, uint64, string, string, []outbox.Record) error
}
type Item struct {
	SourceItemID string
	EvidenceTime time.Time
	Version      string
	VersionTime  time.Time
	EvidenceRef  string
	Payload      []byte
}
type Page struct {
	Items      []Item
	NextCursor string
	End        bool
	Gaps       []string
}
type Adapter interface {
	Request(client.RunClaim, string) (fetcher.Request, error)
	Parse(client.RunClaim, fetcher.Result) (Page, error)
	Freeze(client.RunClaim, Item, time.Time) (outbox.Record, error)
}

// CompletedCursor proves a persisted source snapshot is exhausted without refetching it.
type CompletedCursor interface {
	Completed(client.RunClaim, string) (bool, []string, error)
}
type Outcome struct {
	Cursor       string
	Pages        int
	Observations int
	Finished     bool
	CoverageGaps []string
}
type Scheduler struct {
	fetcher     Fetcher
	store       Store
	adapter     Adapter
	now         func() time.Time
	maxPages    int
	maxDuration time.Duration
}

func New(fetch Fetcher, store Store, adapter Adapter, now func() time.Time, maxPages int, maxDuration time.Duration) (*Scheduler, error) {
	if fetch == nil || store == nil || adapter == nil || now == nil || maxPages < 1 || maxPages > 100 || maxDuration <= 0 {
		return nil, errors.New("bounded scheduler dependencies required")
	}
	return &Scheduler{fetcher: fetch, store: store, adapter: adapter, now: now, maxPages: maxPages, maxDuration: maxDuration}, nil
}

func (s *Scheduler) Run(ctx context.Context, claim client.RunClaim) (Outcome, error) {
	if claim.ClaimToken == nil || claim.LeaseUntil == nil || !claim.CurrentLeaseValid {
		return Outcome{}, outbox.ErrStaleRun
	}
	state, revision, err := s.store.Run(ctx, claim.Run.RunID)
	if err != nil {
		return Outcome{}, err
	}
	priorCount, err := s.store.ObservationCount(ctx, claim.Run.RunID)
	if err != nil {
		return Outcome{}, err
	}
	if state.Frozen.Run.RunID != claim.Run.RunID || state.Frozen.Run.SourceID != claim.Run.SourceID ||
		state.Frozen.Run.Generation != claim.Run.Generation || state.Frozen.Run.ConfigVersion != claim.Run.ConfigVersion ||
		state.Frozen.Run.FetchMode != claim.Run.FetchMode || state.Frozen.Run.WindowStart != claim.Run.WindowStart ||
		state.Frozen.Run.WindowEnd != claim.Run.WindowEnd {
		return Outcome{}, ErrRunChanged
	}
	start, err := parseTime(claim.Run.WindowStart)
	if err != nil {
		return Outcome{}, err
	}
	end, err := parseTime(claim.Run.WindowEnd)
	if err != nil || !start.Before(end) {
		return Outcome{}, ErrRunChanged
	}
	lease, err := parseTime(*claim.LeaseUntil)
	if err != nil || !s.now().Before(lease) {
		return Outcome{}, outbox.ErrStaleRun
	}
	if claim.Run.FetchMode != "daily_delta" && claim.Run.FetchMode != "materialize" {
		return Outcome{}, ErrRunChanged
	}
	target, err := parseTarget(claim.TargetVersion)
	if err != nil {
		return Outcome{}, err
	}
	deadline := s.now().Add(s.maxDuration)
	out := Outcome{Cursor: state.Cursor}
	seen := map[string]bool{}
	for out.Pages < s.maxPages && s.now().Before(deadline) {
		if !s.now().Before(lease) {
			return out, outbox.ErrStaleRun
		}
		if resumed, ok := s.adapter.(CompletedCursor); ok {
			finished, gaps, err := resumed.Completed(claim, out.Cursor)
			if err != nil {
				return out, err
			}
			if finished {
				out.Finished = true
				out.CoverageGaps = append(out.CoverageGaps, gaps...)
				return out, nil
			}
		}
		request, err := s.adapter.Request(claim, out.Cursor)
		if err != nil {
			return out, err
		}
		if request.SourceID != claim.Run.SourceID {
			return out, ErrRunChanged
		}
		if claim.Run.FetchMode == "materialize" {
			request.IfNoneMatch = ""
			request.IfModifiedSince = ""
		}
		var response fetcher.Result
		for attempt := 0; attempt < 2; attempt++ {
			response, err = s.fetcher.Fetch(ctx, request)
			if err != nil {
				return out, err
			}
			if response.StatusCode != http.StatusNotModified || claim.Run.FetchMode != "materialize" {
				break
			}
		}
		if response.StatusCode == http.StatusNotModified {
			if claim.Run.FetchMode == "materialize" {
				out.CoverageGaps = append(out.CoverageGaps, "materialize_unexpected_304")
			}
			out.Finished = true
			return out, nil
		}
		if response.StatusCode != http.StatusOK {
			return out, fmt.Errorf("source response status %d", response.StatusCode)
		}
		page, err := s.adapter.Parse(claim, response)
		if err != nil {
			return out, fmt.Errorf("%w: %v", ErrUnusablePage, err)
		}
		if !page.End && (page.NextCursor == "" || page.NextCursor == out.Cursor) {
			return out, ErrUnusablePage
		}
		records := make([]outbox.Record, 0, len(page.Items))
		for _, item := range page.Items {
			if item.SourceItemID == "" || item.EvidenceTime.IsZero() || len(item.Payload) == 0 {
				return out, ErrUnusablePage
			}
			if !item.EvidenceTime.Before(start) && item.EvidenceTime.Before(end) &&
				target.matches(item) && !seen[item.SourceItemID] {
				record, err := s.adapter.Freeze(claim, item, s.now())
				if err != nil {
					return out, err
				}
				if record.RunID != claim.Run.RunID || record.SourceID != claim.Run.SourceID || record.MemberID == "" {
					return out, ErrUnusablePage
				}
				already, err := s.store.HasMember(ctx, claim.Run.RunID, record.MemberID)
				if err != nil {
					return out, err
				}
				if already {
					seen[item.SourceItemID] = true
					continue
				}
				records = append(records, record)
				seen[item.SourceItemID] = true
			}
		}
		if err := s.store.SavePage(ctx, claim.Run.RunID, *claim.ClaimToken, revision, out.Cursor, page.NextCursor, records); err != nil {
			return out, err
		}
		revision++
		out.Pages++
		out.Observations += len(records)
		out.Cursor = page.NextCursor
		out.CoverageGaps = append(out.CoverageGaps, page.Gaps...)
		if page.End {
			out.Finished = true
			if claim.Run.FetchMode == "materialize" && priorCount+out.Observations == 0 {
				out.CoverageGaps = append(out.CoverageGaps, "target_material_unavailable")
			}
			return out, nil
		}
	}
	return out, nil
}

type targetVersion struct {
	Revision    *string `json:"revision"`
	UpdatedAt   *string `json:"updated_at"`
	EvidenceRef *string `json:"evidence_ref"`
}

func parseTarget(raw json.RawMessage) (targetVersion, error) {
	if len(raw) == 0 || string(raw) == "null" {
		return targetVersion{}, nil
	}
	var target targetVersion
	if err := json.Unmarshal(raw, &target); err != nil {
		return targetVersion{}, err
	}
	if target.UpdatedAt != nil {
		if _, err := parseTime(*target.UpdatedAt); err != nil {
			return targetVersion{}, err
		}
	}
	return target, nil
}

func (target targetVersion) matches(item Item) bool {
	if target.Revision != nil && item.Version != *target.Revision {
		return false
	}
	if target.UpdatedAt != nil {
		if item.VersionTime.IsZero() || item.VersionTime.UTC().Format("2006-01-02T15:04:05.000000Z") != *target.UpdatedAt {
			return false
		}
	}
	if target.EvidenceRef != nil && item.EvidenceRef != *target.EvidenceRef {
		return false
	}
	return true
}

func parseTime(value string) (time.Time, error) {
	return time.Parse("2006-01-02T15:04:05.000000Z", value)
}
