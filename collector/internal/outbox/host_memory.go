package outbox

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"time"
)

const hostMemoryJournalKey = "collector-host-memory-journal-v1"

var ErrHostMemoryJournal = errors.New("collector memory journal unavailable")

type hostMemoryJournal struct {
	SchemaVersion string          `json:"schema_version"`
	NodeID        string          `json:"node_id"`
	State         string          `json:"state"`
	Snapshot      json.RawMessage `json:"snapshot"`
	PayloadHash   string          `json:"payload_hash"`
	Ack           json.RawMessage `json:"ack"`
	revision      uint64
	updated       int64
}
type QueuedHostMemorySnapshot struct {
	Snapshot client.HostMemorySnapshot
	Revision uint64
}

func (QueuedHostMemorySnapshot) String() string { return "QueuedHostMemorySnapshot[redacted]" }
func (s *Store) hostMemoryJournal(ctx context.Context, conn *sql.Conn) (*hostMemoryJournal, error) {
	var raw []byte
	var revision uint64
	var updated int64
	err := conn.QueryRowContext(ctx, "SELECT CASE WHEN length(CAST(value AS BLOB)) BETWEEN 1 AND 16384 THEN value ELSE NULL END,revision,updated_at FROM node_config WHERE key=?", hostMemoryJournalKey).Scan(&raw, &revision, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil || len(raw) == 0 || revision < 1 || revision > maxSafe || updated < 0 || micros(s.now()) < updated {
		return nil, ErrHostMemoryJournal
	}
	v, err := client.ParseStrict(raw)
	if err != nil {
		return nil, ErrHostMemoryJournal
	}
	m, ok := v.(map[string]any)
	if !ok || len(m) != 6 {
		return nil, ErrHostMemoryJournal
	}
	for _, key := range []string{"schema_version", "node_id", "state", "snapshot", "payload_hash", "ack"} {
		if _, ok := m[key]; !ok {
			return nil, ErrHostMemoryJournal
		}
	}
	var j hostMemoryJournal
	if json.Unmarshal(raw, &j) != nil || j.SchemaVersion != hostMemoryJournalKey || j.NodeID != s.nodeID || (j.State != "pending" && j.State != "acknowledged" && j.State != "expired") {
		return nil, ErrHostMemoryJournal
	}
	snapshot, err := client.DecodeHostMemoryTelemetry(j.Snapshot)
	if err != nil || snapshot.PayloadHash != j.PayloadHash || !bytes.Equal(j.Snapshot, snapshot.FrozenWireBytes()) {
		return nil, ErrHostMemoryJournal
	}
	observed, err := time.Parse(client.SourceContractTime, snapshot.ObservedAt)
	if err != nil || micros(observed) > updated {
		return nil, ErrHostMemoryJournal
	}
	if j.State == "acknowledged" {
		if _, err = client.DecodeHostMemoryAck(j.Ack, snapshot, s.now()); err != nil {
			return nil, ErrHostMemoryJournal
		}
	} else if string(j.Ack) != "null" {
		return nil, ErrHostMemoryJournal
	}
	j.revision = revision
	j.updated = updated
	return &j, nil
}
func (s *Store) saveHostMemoryJournal(ctx context.Context, conn *sql.Conn, j *hostMemoryJournal) error {
	raw, err := json.Marshal(j)
	if err != nil || len(raw) > 16384 || j.revision >= maxSafe {
		return ErrHostMemoryJournal
	}
	var changed sql.Result
	if j.revision == 0 {
		changed, err = conn.ExecContext(ctx, "INSERT INTO node_config(key,value,revision,updated_at) VALUES(?,?,1,?)", hostMemoryJournalKey, raw, micros(s.now()))
	} else {
		changed, err = conn.ExecContext(ctx, "UPDATE node_config SET value=?,revision=revision+1,updated_at=? WHERE key=? AND revision=?", raw, micros(s.now()), hostMemoryJournalKey, j.revision)
	}
	if err != nil {
		return err
	}
	count, err := changed.RowsAffected()
	if err != nil || count != 1 {
		return ErrHostMemoryJournal
	}
	return nil
}
func hostMemoryQueued(j *hostMemoryJournal) (QueuedHostMemorySnapshot, error) {
	v, err := client.DecodeHostMemoryTelemetry(j.Snapshot)
	if err != nil {
		return QueuedHostMemorySnapshot{}, ErrHostMemoryJournal
	}
	return QueuedHostMemorySnapshot{v, j.revision}, nil
}

// One bounded node_config record preserves frozen bytes. SQLite schema v3 is untouched.
func (s *Store) PendingHostMemorySnapshot(ctx context.Context) (*QueuedHostMemorySnapshot, error) {
	var result *QueuedHostMemorySnapshot
	err := s.write(ctx, func(conn *sql.Conn) error {
		j, err := s.hostMemoryJournal(ctx, conn)
		if err != nil || j == nil || j.State != "pending" {
			return err
		}
		q, err := hostMemoryQueued(j)
		if err == nil {
			result = &q
		}
		return err
	})
	return result, err
}
func (s *Store) StageHostMemorySnapshot(ctx context.Context, input client.HostMemorySnapshot) (QueuedHostMemorySnapshot, error) {
	var result QueuedHostMemorySnapshot
	err := s.write(ctx, func(conn *sql.Conn) error {
		before, err := s.hostMemoryJournal(ctx, conn)
		if err != nil {
			return err
		}
		if before != nil && before.State == "pending" {
			result, err = hostMemoryQueued(before)
			return err
		}
		snapshot, err := client.DecodeHostMemoryTelemetry(input.FrozenWireBytes())
		if err != nil {
			return ErrHostMemoryJournal
		}
		observed, _ := time.Parse(client.SourceContractTime, snapshot.ObservedAt)
		at := s.now().UTC().Truncate(time.Microsecond)
		if observed.After(at) || at.Sub(observed) > 120*time.Second {
			return ErrHostMemoryJournal
		}
		revision := uint64(0)
		if before != nil {
			old, err := client.DecodeHostMemoryTelemetry(before.Snapshot)
			if err != nil {
				return ErrHostMemoryJournal
			}
			prior, _ := time.Parse(client.SourceContractTime, old.ObservedAt)
			if !observed.After(prior) {
				return ErrHostMemoryJournal
			}
			if old.CaptureEpoch == snapshot.CaptureEpoch {
				if old.ProcessIdentity != snapshot.ProcessIdentity || old.PID != snapshot.PID || old.CaptureStartedAt != snapshot.CaptureStartedAt || snapshot.Sequence <= old.Sequence {
					return ErrHostMemoryJournal
				}
			} else {
				started, _ := time.Parse(client.SourceContractTime, snapshot.CaptureStartedAt)
				if !started.After(prior) {
					return ErrHostMemoryJournal
				}
			}
			if old.CgroupEpoch == snapshot.CgroupEpoch && (snapshot.OOMEvents < old.OOMEvents || snapshot.OOMKills < old.OOMKills) {
				return ErrHostMemoryJournal
			}
			revision = before.revision
		}
		j := &hostMemoryJournal{SchemaVersion: hostMemoryJournalKey, NodeID: s.nodeID, State: "pending", Snapshot: snapshot.FrozenWireBytes(), PayloadHash: snapshot.PayloadHash, Ack: json.RawMessage("null"), revision: revision}
		if err = s.saveHostMemoryJournal(ctx, conn, j); err != nil {
			return err
		}
		result = QueuedHostMemorySnapshot{snapshot, revision + 1}
		return nil
	})
	return result, err
}
func (s *Store) ApplyHostMemoryAck(ctx context.Context, queued QueuedHostMemorySnapshot, ack client.HostMemoryAck) error {
	return s.write(ctx, func(conn *sql.Conn) error {
		j, err := s.hostMemoryJournal(ctx, conn)
		if err != nil || j == nil || !bytes.Equal(j.Snapshot, queued.Snapshot.FrozenWireBytes()) {
			return ErrHostMemoryJournal
		}
		snapshot, err := client.DecodeHostMemoryTelemetry(j.Snapshot)
		if err != nil {
			return ErrHostMemoryJournal
		}
		verified, err := client.DecodeHostMemoryAck(ack.FrozenWireBytes(), snapshot, s.now())
		if err != nil {
			return ErrHostMemoryJournal
		}
		if j.State == "acknowledged" {
			if !bytes.Equal(j.Ack, verified.FrozenWireBytes()) {
				return ErrHostMemoryJournal
			}
			return nil
		}
		if j.State != "pending" || queued.Revision != j.revision {
			return ErrHostMemoryJournal
		}
		j.State = "acknowledged"
		j.Ack = verified.FrozenWireBytes()
		return s.saveHostMemoryJournal(ctx, conn, j)
	})
}

// Caller must first attempt original ACK replay and receive this native typed rejection.
func (s *Store) RetireRejectedHostMemorySnapshot(ctx context.Context, queued QueuedHostMemorySnapshot, rejection *client.HostMemoryRejected) error {
	if rejection == nil || !rejection.ForSnapshot(queued.Snapshot) || (rejection.Status() != 409 && rejection.Status() != 422) {
		return ErrHostMemoryJournal
	}
	return s.write(ctx, func(conn *sql.Conn) error {
		j, err := s.hostMemoryJournal(ctx, conn)
		if err != nil || j == nil || !bytes.Equal(j.Snapshot, queued.Snapshot.FrozenWireBytes()) {
			return ErrHostMemoryJournal
		}
		snapshot, err := client.DecodeHostMemoryTelemetry(j.Snapshot)
		if err != nil {
			return ErrHostMemoryJournal
		}
		observed, _ := time.Parse(client.SourceContractTime, snapshot.ObservedAt)
		if s.now().Sub(observed) <= 120*time.Second {
			return ErrHostMemoryJournal
		}
		if j.State == "expired" {
			return nil
		}
		if j.State != "pending" || j.revision != queued.Revision {
			return ErrHostMemoryJournal
		}
		j.State = "expired"
		return s.saveHostMemoryJournal(ctx, conn, j)
	})
}
