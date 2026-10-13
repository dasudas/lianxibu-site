package outbox

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"time"
)

const sourceJournalSchema = "source-contract-journal-v1"
const SourceContractRecordKind = "source_contract_snapshot"

var ErrSourceContractJournal = errors.New("source contract journal unavailable")

type sourceContractJournal struct {
	InFlight         string                      `json:"in_flight"`
	SchemaVersion    string                      `json:"schema_version"`
	NodeID           string                      `json:"node_id"`
	SourceID         string                      `json:"source_id"`
	ConfigVersion    uint64                      `json:"config_version"`
	Epoch            string                      `json:"epoch"`
	SnapshotSequence uint64                      `json:"snapshot_sequence"`
	TotalFailures    uint64                      `json:"total_failures"`
	ObservedAt       string                      `json:"observed_at"`
	Events           []client.SourceFailureEvent `json:"events"`
	PendingID        string                      `json:"pending_id"`
}
type QueuedSourceContractSnapshot struct {
	RecordID string
	Snapshot client.SourceContractSnapshot
	Revision uint64
}

func (QueuedSourceContractSnapshot) String() string { return "QueuedSourceContractSnapshot[redacted]" }
func sourceJournalKey(id string, ver uint64) string {
	raw, _ := json.Marshal([]any{id, ver})
	hash := sha256.Sum256(raw)
	return "source-contract:" + hex.EncodeToString(hash[:])
}
func (s *Store) sourceJournal(ctx context.Context, conn *sql.Conn, id string, version uint64, at time.Time) (sourceContractJournal, error) {
	var raw []byte
	err := conn.QueryRowContext(ctx, "SELECT CASE WHEN length(value)<=65536 THEN value ELSE NULL END FROM node_config WHERE key=?", sourceJournalKey(id, version)).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		var prior int
		if err = conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM outbox WHERE record_kind=? AND source_id=? AND json_extract(payload,'$.config_version')=?", SourceContractRecordKind, id, version).Scan(&prior); err != nil {
			return sourceContractJournal{}, err
		}
		if prior != 0 {
			return sourceContractJournal{}, ErrSourceContractJournal
		}
		epoch, err := randomID()
		if err != nil {
			return sourceContractJournal{}, err
		}
		epoch = epoch[:8] + "-" + epoch[8:12] + "-" + epoch[12:16] + "-" + epoch[16:20] + "-" + epoch[20:]
		j := sourceContractJournal{SchemaVersion: sourceJournalSchema, NodeID: s.nodeID, SourceID: id, ConfigVersion: version, Epoch: epoch, ObservedAt: at.Format(client.SourceContractTime), Events: []client.SourceFailureEvent{}}
		if _, err = client.NewSourceContractTelemetry(id, version, epoch, 1, at, 0, j.Events); err != nil {
			return j, ErrSourceContractJournal
		}
		return j, nil
	}
	if err != nil {
		return sourceContractJournal{}, err
	}
	if len(raw) == 0 {
		return sourceContractJournal{}, ErrSourceContractJournal
	}
	value, err := client.ParseStrict(raw)
	if err != nil {
		return sourceContractJournal{}, ErrSourceContractJournal
	}
	m, ok := value.(map[string]any)
	if !ok || len(m) != 11 {
		return sourceContractJournal{}, ErrSourceContractJournal
	}
	for _, key := range []string{"in_flight", "schema_version", "node_id", "source_id", "config_version", "epoch", "snapshot_sequence", "total_failures", "observed_at", "events", "pending_id"} {
		if _, ok = m[key]; !ok {
			return sourceContractJournal{}, ErrSourceContractJournal
		}
	}
	var j sourceContractJournal
	if json.Unmarshal(raw, &j) != nil || j.SchemaVersion != sourceJournalSchema || j.NodeID != s.nodeID || j.SourceID != id || j.ConfigVersion != version || j.SnapshotSequence > maxSafe {
		return j, ErrSourceContractJournal
	}
	observed, err := time.Parse(client.SourceContractTime, j.ObservedAt)
	if err != nil || at.Before(observed) {
		return j, ErrSourceContractJournal
	}
	sequence := j.SnapshotSequence
	if sequence == 0 {
		sequence = 1
	}
	if _, err = client.NewSourceContractTelemetry(id, version, j.Epoch, sequence, observed, j.TotalFailures, j.Events); err != nil {
		return j, ErrSourceContractJournal
	}
	if j.InFlight != "" {
		if len(j.InFlight) != 32 {
			return j, ErrSourceContractJournal
		}
		if _, err := hex.DecodeString(j.InFlight); err != nil {
			return j, ErrSourceContractJournal
		}
	}
	if j.PendingID != "" && (len(j.PendingID) != 32 || j.SnapshotSequence == 0) {
		return j, ErrSourceContractJournal
	}
	return j, nil
}
func (s *Store) saveSourceJournal(ctx context.Context, conn *sql.Conn, j sourceContractJournal) error {
	raw, err := json.Marshal(j)
	if err != nil || len(raw) > 65536 {
		return ErrSourceContractJournal
	}
	result, err := conn.ExecContext(ctx, `INSERT INTO node_config(key,value,revision,updated_at) VALUES(?,?,1,?)
 ON CONFLICT(key) DO UPDATE SET value=excluded.value,revision=node_config.revision+1,updated_at=excluded.updated_at WHERE node_config.revision<9007199254740991`, sourceJournalKey(j.SourceID, j.ConfigVersion), raw, micros(s.now()))
	if err != nil {
		return err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if changed != 1 {
		return ErrSourceContractJournal
	}
	return nil
}
func pruneSourceEvents(events []client.SourceFailureEvent, at time.Time) []client.SourceFailureEvent {
	result := make([]client.SourceFailureEvent, 0, len(events))
	for _, e := range events {
		when, err := time.Parse(client.SourceContractTime, e.OccurredAt)
		if err == nil && when.After(at.Add(-600*time.Second)) {
			result = append(result, e)
		}
	}
	return result
}

// CaptureSourceContractFailure commits an actual parser failure before further input.
// Network errors must never be passed here. A failed commit stops the caller.
func (s *Store) CaptureSourceContractFailure(ctx context.Context, id string, version uint64) error {
	at := s.now().UTC().Truncate(time.Microsecond)
	return s.write(ctx, func(conn *sql.Conn) error {
		j, err := s.sourceJournal(ctx, conn, id, version, at)
		if err != nil {
			return err
		}
		events := pruneSourceEvents(j.Events, at)
		if j.TotalFailures == maxSafe || len(events) >= 200 {
			return ErrSourceContractJournal
		}
		j.TotalFailures++
		j.ObservedAt = at.Format(client.SourceContractTime)
		j.Events = append(events, client.SourceFailureEvent{Sequence: j.TotalFailures, OccurredAt: j.ObservedAt})
		return s.saveSourceJournal(ctx, conn, j)
	})
}

// StageSourceContractSnapshot returns the original pending bytes across restart.
// Capture can continue while an older snapshot awaits its original ACK.
func (s *Store) StageSourceContractSnapshot(ctx context.Context, id string, version uint64) (QueuedSourceContractSnapshot, error) {
	at := s.now().UTC().Truncate(time.Microsecond)
	var result QueuedSourceContractSnapshot
	err := s.write(ctx, func(conn *sql.Conn) error {
		j, err := s.sourceJournal(ctx, conn, id, version, at)
		if err != nil {
			return err
		}
		if j.InFlight != "" {
			return ErrSourceContractJournal
		}
		if j.PendingID != "" {
			result, err = s.readSourceContractSnapshot(ctx, conn, j)
			return err
		}
		if j.SnapshotSequence == maxSafe {
			return ErrSourceContractJournal
		}
		j.SnapshotSequence++
		j.ObservedAt = at.Format(client.SourceContractTime)
		j.Events = pruneSourceEvents(j.Events, at)
		snapshot, err := client.NewSourceContractTelemetry(id, version, j.Epoch, j.SnapshotSequence, at, j.TotalFailures, j.Events)
		if err != nil {
			return ErrSourceContractJournal
		}
		record, err := randomID()
		if err != nil {
			return err
		}
		identity, _ := json.Marshal([]any{s.nodeID, id, j.Epoch, j.SnapshotSequence})
		hash := sha256.Sum256(identity)
		_, err = conn.ExecContext(ctx, `INSERT INTO outbox(id,record_kind,record_key,source_id,payload,payload_hash,protocol_revision,hash_version,next_retry_at,created_at)
   VALUES(?,?,?,?,?,?,?,1,?,?)`, record, SourceContractRecordKind, hex.EncodeToString(hash[:]), id, snapshot.FrozenWireBytes(), snapshot.PayloadHash, client.SourceContractSchema, micros(at), micros(at))
		if err != nil {
			return err
		}
		j.PendingID = record
		if err = s.saveSourceJournal(ctx, conn, j); err != nil {
			return err
		}
		result = QueuedSourceContractSnapshot{RecordID: record, Snapshot: snapshot}
		return nil
	})
	return result, err
}
func (s *Store) readSourceContractSnapshot(ctx context.Context, conn *sql.Conn, j sourceContractJournal) (QueuedSourceContractSnapshot, error) {
	var raw []byte
	var source, hash, protocol, state, recordKey string
	var revision uint64
	var hashVersion int
	err := conn.QueryRowContext(ctx, `SELECT CASE WHEN length(payload)<=32768 THEN payload ELSE NULL END,source_id,payload_hash,protocol_revision,hash_version,state,revision,record_key FROM outbox WHERE id=? AND record_kind=? AND run_id IS NULL AND observation_id IS NULL AND business_ref IS NULL AND idempotency_key IS NULL`, j.PendingID, SourceContractRecordKind).Scan(&raw, &source, &hash, &protocol, &hashVersion, &state, &revision, &recordKey)
	if err != nil {
		return QueuedSourceContractSnapshot{}, ErrSourceContractJournal
	}
	snapshot, err := client.DecodeSourceContractTelemetry(raw)
	if err != nil {
		return QueuedSourceContractSnapshot{}, ErrSourceContractJournal
	}
	identity, _ := json.Marshal([]any{s.nodeID, j.SourceID, j.Epoch, j.SnapshotSequence})
	keyHash := sha256.Sum256(identity)
	if snapshot.ObservedAt > j.ObservedAt || source != j.SourceID || snapshot.SourceID != source || snapshot.ConfigVersion != j.ConfigVersion || snapshot.Epoch != j.Epoch || snapshot.Sequence != j.SnapshotSequence || snapshot.TotalFailures > j.TotalFailures || hash != snapshot.PayloadHash || protocol != client.SourceContractSchema || hashVersion != 1 || state != "pending" || recordKey != hex.EncodeToString(keyHash[:]) || revision > maxSafe || !bytes.Equal(raw, snapshot.FrozenWireBytes()) {
		return QueuedSourceContractSnapshot{}, ErrSourceContractJournal
	}
	return QueuedSourceContractSnapshot{RecordID: j.PendingID, Snapshot: snapshot, Revision: revision}, nil
}
