package fetcher

import (
	"context"
	"errors"
	"net/http"
	"path/filepath"
	"testing"
	"time"
)

func TestHostBudgetSurvivesRestartAndSharesAcrossInstances(t *testing.T) {
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }
	path := filepath.Join(t.TempDir(), "budget.db")
	b, err := openBudget(path, clock)
	if err != nil {
		t.Fatal(err)
	}
	other, err := openBudget(path, clock)
	if err != nil {
		t.Fatal(err)
	}
	defer other.db.Close()
	release, err := b.acquire(context.Background(), "export.arxiv.org")
	if err != nil {
		t.Fatal(err)
	}
	now = now.Add(4 * time.Second)
	if _, err = other.acquire(context.Background(), "arxiv.org"); !errors.Is(err, ErrHostDeferred) {
		t.Fatal("parallel connection allowed", err)
	}
	release()
	release, err = other.acquire(context.Background(), "arxiv.org")
	if err != nil {
		t.Fatal(err)
	}
	release()
	b.db.Close()
	b, err = openBudget(path, clock)
	if err != nil {
		t.Fatal(err)
	}
	defer b.db.Close()
	if _, err = b.acquire(context.Background(), "export.arxiv.org"); !errors.Is(err, ErrHostDeferred) {
		t.Fatal("restart reset interval")
	}
	for i := 0; i < 60; i++ {
		now = now.Add(time.Second)
		r, e := b.acquire(context.Background(), "api.github.com")
		if e != nil {
			t.Fatal(i, e)
		}
		r()
	}
	now = now.Add(time.Second)
	if _, err = other.acquire(context.Background(), "api.github.com"); !errors.Is(err, ErrHostDeferred) {
		t.Fatal("shared hourly cap bypassed")
	}
	now = now.Add(time.Hour)
	r, err := b.acquire(context.Background(), "api.github.com")
	if err != nil {
		t.Fatal(err)
	}
	r()
	b.observe("api.github.com", &http.Response{StatusCode: 429, Header: http.Header{"Retry-After": []string{"7200"}}})
	now = now.Add(time.Hour)
	if _, err = other.acquire(context.Background(), "api.github.com"); !errors.Is(err, ErrHostDeferred) {
		t.Fatal("retry-after ignored")
	}
}

func TestFailedPausePersistenceBlocksFurtherRequests(t *testing.T) {
	clock := func() time.Time { return time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC) }
	b, err := openBudget(filepath.Join(t.TempDir(), "budget.db"), clock)
	if err != nil {
		t.Fatal(err)
	}
	defer b.db.Close()
	release, err := b.acquire(context.Background(), "api.github.com")
	if err != nil {
		t.Fatal(err)
	}
	release()
	if _, err = b.db.Exec("CREATE TRIGGER reject_pause BEFORE UPDATE OF next_at ON host_budget BEGIN SELECT RAISE(FAIL,'fixture write failure'); END"); err != nil {
		t.Fatal(err)
	}
	if err = b.observe("api.github.com", &http.Response{StatusCode: 429, Header: http.Header{}}); !errors.Is(err, ErrHostBudgetUnavailable) {
		t.Fatal("pause persistence failure ignored", err)
	}
	if _, err = b.db.Exec("DROP TRIGGER reject_pause"); err != nil {
		t.Fatal(err)
	}
	if _, err = b.acquire(context.Background(), "fixture.example.invalid"); !errors.Is(err, ErrHostBudgetUnavailable) {
		t.Fatal("poisoned process continued fetching", err)
	}
}

func TestDeferredTimeSurvivesRestart(t *testing.T) {
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	path := filepath.Join(t.TempDir(), "budget.db")
	b, err := openBudget(path, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	r, err := b.acquire(context.Background(), "api.github.com")
	if err != nil {
		t.Fatal(err)
	}
	check := func(want time.Time) {
		t.Helper()
		_, e := b.acquire(context.Background(), "api.github.com")
		var deferred *HostDeferredError
		if !errors.Is(e, ErrHostDeferred) || !errors.As(e, &deferred) || !deferred.RetryAt.Equal(want) {
			t.Fatalf("unexpected deferred time: %v", e)
		}
	}
	check(now.Add(35 * time.Second))
	r()
	check(now.Add(time.Second))
	if _, err = b.db.Exec("UPDATE host_budget SET used=60"); err != nil {
		t.Fatal(err)
	}
	check(now.Add(time.Hour))
	if err = b.observe("api.github.com", &http.Response{StatusCode: 429, Header: http.Header{"Retry-After": []string{"7200"}}}); err != nil {
		t.Fatal(err)
	}
	b.db.Close()
	b, err = openBudget(path, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	defer b.db.Close()
	check(now.Add(2 * time.Hour))
}
