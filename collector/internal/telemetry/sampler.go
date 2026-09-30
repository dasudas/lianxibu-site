package telemetry

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

type SQLiteSampler struct {
	path string
	db   *sql.DB
}

func NewSQLiteSampler(path string) (*SQLiteSampler, error) {
	if path == "" {
		return nil, errors.New("queue path required")
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("queue path is not a file")
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	return &SQLiteSampler{path: path, db: db}, nil
}

func (s *SQLiteSampler) Close() error { return s.db.Close() }

func (s *SQLiteSampler) Sample(ctx context.Context) (Sample, error) {
	if err := ctx.Err(); err != nil {
		return Sample{}, err
	}
	var pendingObs, pendingControls, quarantined uint64
	var oldest sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT
COALESCE(SUM(CASE WHEN record_kind='observation' AND state IN ('pending','sending') THEN 1 ELSE 0 END),0),
COALESCE(SUM(CASE WHEN record_kind='control_attempt' AND state IN ('pending','sending') THEN 1 ELSE 0 END),0),
COALESCE(SUM(CASE WHEN state='quarantine' THEN 1 ELSE 0 END),0),
MIN(CASE WHEN record_kind IN ('observation','control_attempt') AND state IN ('pending','sending') THEN created_at ELSE NULL END)
FROM outbox`).Scan(&pendingObs, &pendingControls, &quarantined, &oldest)
	if err != nil {
		return Sample{}, err
	}
	info, err := os.Stat(s.path)
	if err != nil {
		return Sample{}, err
	}
	if !info.Mode().IsRegular() || info.Size() < 0 {
		return Sample{}, errors.New("queue storage unavailable")
	}
	size := uint64(info.Size())
	wal, err := os.Stat(s.path + "-wal")
	if err == nil {
		if !wal.Mode().IsRegular() || wal.Size() < 0 {
			return Sample{}, errors.New("queue WAL unavailable")
		}
		size += uint64(wal.Size())
	} else if !errors.Is(err, os.ErrNotExist) {
		return Sample{}, err
	}
	free, err := diskFree(filepath.Dir(s.path))
	if err != nil {
		return Sample{}, err
	}
	rss, err := processRSS()
	if err != nil {
		return Sample{}, err
	}
	sample := Sample{PendingObservations: pendingObs, PendingControls: pendingControls, Quarantined: quarantined, OutboxBytes: size, FreeBytes: free, RSSBytes: rss}
	if oldest.Valid {
		value := time.UnixMicro(oldest.Int64).UTC()
		sample.OldestPendingAt = &value
	}
	return sample, nil
}
