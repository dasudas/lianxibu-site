package fetcher

import (
	"context"
	"database/sql"
	"errors"
	_ "modernc.org/sqlite"
	"net/http"
	"strconv"
	"sync/atomic"
	"time"
)

var ErrHostDeferred = errors.New("source host budget deferred")
var ErrHostBudgetUnavailable = errors.New("source host budget persistence unavailable")

// RetryAt is the persisted budget's earliest retry time, not a reservation.
// Another process may consume the budget before the caller retries.
type HostDeferredError struct{ RetryAt time.Time }

func (e *HostDeferredError) Error() string { return ErrHostDeferred.Error() }
func (e *HostDeferredError) Unwrap() error { return ErrHostDeferred }

type hostBudget struct {
	db     *sql.DB
	now    func() time.Time
	failed atomic.Bool
}

// NewPersistent shares host budgets across source IDs and process restarts.
// All collector processes on this node must use the same host-owned database.
func NewPersistent(path string) (*Fetcher, error) {
	b, err := openBudget(path, time.Now)
	if err != nil {
		return nil, err
	}
	f := New()
	f.budget = b
	return f, nil
}
func (f *Fetcher) Close() error {
	if f.budget != nil {
		return f.budget.db.Close()
	}
	return nil
}
func openBudget(path string, now func() time.Time) (*hostBudget, error) {
	if path == "" || now == nil {
		return nil, errors.New("host budget required")
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	for _, q := range []string{"PRAGMA busy_timeout=5000", "PRAGMA journal_mode=WAL", "PRAGMA synchronous=FULL",
		"CREATE TABLE IF NOT EXISTS host_budget(host TEXT PRIMARY KEY,window_start INTEGER NOT NULL,used INTEGER NOT NULL,next_at INTEGER NOT NULL,lease_until INTEGER NOT NULL,lease_generation INTEGER NOT NULL)"} {
		if _, err = db.Exec(q); err != nil {
			db.Close()
			return nil, err
		}
	}
	return &hostBudget{db: db, now: now}, nil
}
func budgetHost(host string) (string, time.Duration, int) {
	switch host {
	case "export.arxiv.org", "arxiv.org", "www.arxiv.org":
		return "arxiv.org", 3 * time.Second, 0
	case "api.github.com":
		return host, time.Second, 60
	default:
		return host, 0, 0
	}
}
func (b *hostBudget) acquire(ctx context.Context, host string) (func(), error) {
	if b.failed.Load() {
		return nil, ErrHostBudgetUnavailable
	}
	host, interval, max := budgetHost(host)
	now := b.now().UnixMilli()
	// A single conditional UPDATE grants one lease and consumes budget atomically.
	if _, err := b.db.ExecContext(ctx, "INSERT OR IGNORE INTO host_budget VALUES(?,?,0,0,0,0)", host, now); err != nil {
		return nil, err
	}
	result, err := b.db.ExecContext(ctx, `UPDATE host_budget SET
 used=CASE WHEN window_start<=? THEN 1 ELSE used+1 END,
 window_start=CASE WHEN window_start<=? THEN ? ELSE window_start END,
 next_at=?,lease_until=?,lease_generation=lease_generation+1
 WHERE host=? AND next_at<=? AND lease_until<=? AND (?=0 OR window_start<=? OR used<?)`,
		now-3600000, now-3600000, now, now+interval.Milliseconds(), now+35000, host, now, now, max, now-3600000, max)
	if err != nil {
		return nil, err
	}
	n, err := result.RowsAffected()
	if err != nil {
		return nil, ErrHostBudgetUnavailable
	}
	if n != 1 {
		var start, used, next, lease int64
		if err := b.db.QueryRowContext(ctx, "SELECT window_start,used,next_at,lease_until FROM host_budget WHERE host=?", host).Scan(&start, &used, &next, &lease); err != nil {
			return nil, ErrHostBudgetUnavailable
		}
		if lease > next {
			next = lease
		}
		if max > 0 && used >= int64(max) && start+3600000 > next {
			next = start + 3600000
		}
		// A concurrent lease release can make the row immediately eligible.
		if next <= now {
			next = now + 1
		}
		return nil, &HostDeferredError{RetryAt: time.UnixMilli(next).UTC()}
	}
	var generation int64
	if err = b.db.QueryRowContext(ctx, "SELECT lease_generation FROM host_budget WHERE host=?", host).Scan(&generation); err != nil {
		return nil, err
	}
	return func() {
		_, _ = b.db.Exec("UPDATE host_budget SET lease_until=0 WHERE host=? AND lease_generation=?", host, generation)
	}, nil
}
func (b *hostBudget) observe(host string, response *http.Response) error {
	if b.failed.Load() {
		return ErrHostBudgetUnavailable
	}
	host, _, _ = budgetHost(host)
	if response.StatusCode != 429 && !(response.StatusCode == 403 && response.Header.Get("X-RateLimit-Remaining") == "0") && response.Header.Get("X-RateLimit-Remaining") != "0" {
		return nil
	}
	until := b.now().Add(time.Hour)
	if seconds, err := strconv.ParseInt(response.Header.Get("Retry-After"), 10, 64); err == nil && seconds >= 0 && seconds < 86400*30 {
		if candidate := b.now().Add(time.Duration(seconds) * time.Second); candidate.After(until) {
			until = candidate
		}
	}
	if date, err := http.ParseTime(response.Header.Get("Retry-After")); err == nil && date.After(until) {
		until = date
	}
	if seconds, err := strconv.ParseInt(response.Header.Get("X-RateLimit-Reset"), 10, 64); err == nil {
		if candidate := time.Unix(seconds, 0); candidate.After(until) {
			until = candidate
		}
	}
	result, err := b.db.Exec("UPDATE host_budget SET next_at=MAX(next_at,?) WHERE host=?", until.UnixMilli(), host)
	if err == nil {
		var count int64
		count, err = result.RowsAffected()
		if count != 1 {
			err = ErrHostBudgetUnavailable
		}
	}
	if err != nil {
		b.failed.Store(true)
		return ErrHostBudgetUnavailable
	}
	return nil
}
