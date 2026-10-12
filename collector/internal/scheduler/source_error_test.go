package scheduler

import (
	"context"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"net/http"
	"strings"
	"testing"
	"time"
)

type formatAdapter struct {
	fixtureAdapter
	parseErr, freezeErr error
}

func (a formatAdapter) Parse(c client.RunClaim, r fetcher.Result) (Page, error) {
	if a.parseErr != nil {
		return Page{}, a.parseErr
	}
	return a.fixtureAdapter.Parse(c, r)
}
func (a formatAdapter) Freeze(c client.RunClaim, i Item, at time.Time) (outbox.Record, error) {
	if a.freezeErr != nil {
		return outbox.Record{}, a.freezeErr
	}
	return a.fixtureAdapter.Freeze(c, i, at)
}

type failureFetch struct {
	err    error
	status int
}

func (f failureFetch) Fetch(context.Context, fetcher.Request) (fetcher.Result, error) {
	return fetcher.Result{StatusCode: f.status}, f.err
}
func TestSourceFormatFailureClassificationAndCheckpoint(t *testing.T) {
	for _, stage := range []string{"parse", "freeze"} {
		t.Run(stage, func(t *testing.T) {
			c := claim("daily_delta")
			store := &memoryStore{state: outbox.RunState{Frozen: c, Cursor: "original-cursor"}, revision: 3}
			in := item("fictional-item", "v1", time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC))
			fetch := &memoryFetch{responses: []fetcher.Result{result([]Item{in}, "next", true)}}
			adapter := formatAdapter{}
			cause := errors.New("fictional private page material")
			if stage == "parse" {
				adapter.parseErr = cause
			} else {
				adapter.freezeErr = cause
			}
			s, err := New(fetch, store, adapter, clock, 2, time.Minute)
			if err != nil {
				t.Fatal(err)
			}
			_, err = s.Run(context.Background(), c)
			if !errors.Is(err, ErrUnusablePage) || strings.Contains(err.Error(), "private page") || store.state.Cursor != "original-cursor" || store.revision != 3 || len(store.records) != 0 {
				t.Fatal("format failure did not stay typed/redacted/atomic", err)
			}
		})
	}
}
func TestSourceFormatCancellationAndNetworkAreNotContractFailures(t *testing.T) {
	for _, cause := range []error{context.Canceled, context.DeadlineExceeded} {
		for _, stage := range []string{"parse", "freeze"} {
			c := claim("daily_delta")
			s, _, _ := setup(c, 2, result([]Item{item("fictional-item", "v1", time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC))}, "", true))
			a := formatAdapter{}
			if stage == "parse" {
				a.parseErr = cause
			} else {
				a.freezeErr = cause
			}
			s.adapter = a
			_, err := s.Run(context.Background(), c)
			if !errors.Is(err, cause) || errors.Is(err, ErrUnusablePage) {
				t.Fatal("cancel is not parser failure", err)
			}
		}
	}
	for _, status := range []int{http.StatusTooManyRequests, http.StatusBadGateway, http.StatusForbidden} {
		c := claim("daily_delta")
		s, _, _ := setup(c, 2)
		s.fetcher = failureFetch{status: status}
		_, err := s.Run(context.Background(), c)
		if err == nil || errors.Is(err, ErrUnusablePage) {
			t.Fatal("HTTP response is not contract failure", err)
		}
	}
	cause := errors.New("fictional network timeout")
	c := claim("daily_delta")
	s, _, _ := setup(c, 2)
	s.fetcher = failureFetch{err: cause}
	_, err := s.Run(context.Background(), c)
	if !errors.Is(err, cause) || errors.Is(err, ErrUnusablePage) {
		t.Fatal("network classification", err)
	}
}
