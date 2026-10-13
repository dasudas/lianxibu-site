package outbox

import (
	"bytes"
	"context"
	"database/sql"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"time"
)

// ApplySourceContractAck atomically commits the exact server ACK and clears its pointer.
// A replay reads the original permanent receipt before subsequent journal state.
func (s *Store) ApplySourceContractAck(ctx context.Context, queued QueuedSourceContractSnapshot, ack client.SourceContractAck) error {
	return s.write(ctx, func(conn *sql.Conn) error {
		var payload, storedAck []byte
		var source, hash, protocol, state string
		var revision uint64
		var hashVersion int
		err := conn.QueryRowContext(ctx, `SELECT CASE WHEN length(payload)<=32768 THEN payload ELSE NULL END,source_id,payload_hash,protocol_revision,hash_version,state,revision,
    CASE WHEN length(ack_blob)<=4096 THEN ack_blob ELSE NULL END FROM outbox WHERE id=? AND record_kind=? AND run_id IS NULL AND observation_id IS NULL AND business_ref IS NULL AND idempotency_key IS NULL`, queued.RecordID, SourceContractRecordKind).Scan(&payload, &source, &hash, &protocol, &hashVersion, &state, &revision, &storedAck)
		if err != nil {
			return ErrSourceContractJournal
		}
		original, err := client.DecodeSourceContractTelemetry(payload)
		if err != nil || source != original.SourceID || hash != original.PayloadHash || protocol != client.SourceContractSchema || hashVersion != 1 || !bytes.Equal(payload, queued.Snapshot.FrozenWireBytes()) {
			return ErrSourceContractJournal
		}
		verified, err := client.DecodeSourceContractAck(ack.FrozenWireBytes(), original, s.now())
		if err != nil {
			return ErrSourceContractJournal
		}
		if state == "acknowledged" {
			prior, err := client.DecodeSourceContractAck(storedAck, original, s.now())
			if err != nil || !bytes.Equal(prior.FrozenWireBytes(), verified.FrozenWireBytes()) {
				return ErrSourceContractJournal
			}
			return nil
		}
		if state != "pending" || revision != queued.Revision || revision == maxSafe {
			return ErrSourceContractJournal
		}
		j, err := s.sourceJournal(ctx, conn, original.SourceID, original.ConfigVersion, s.now().UTC().Truncate(time.Microsecond))
		if err != nil || j.PendingID != queued.RecordID {
			return ErrSourceContractJournal
		}
		current, err := s.readSourceContractSnapshot(ctx, conn, j)
		if err != nil || current.RecordID != queued.RecordID {
			return ErrSourceContractJournal
		}
		changed, err := conn.ExecContext(ctx, `UPDATE outbox SET state='acknowledged',revision=revision+1,ack_blob=?,acknowledged_at=?,receipt_ref=? WHERE id=? AND state='pending' AND revision=?`, verified.FrozenWireBytes(), micros(s.now()), verified.PayloadHash, queued.RecordID, revision)
		if err != nil {
			return err
		}
		count, err := changed.RowsAffected()
		if err != nil || count != 1 {
			return ErrSourceContractJournal
		}
		j.PendingID = ""
		return s.saveSourceJournal(ctx, conn, j)
	})
}

// SourceContractSnapshotDue uses the original persisted ACK and observation time.
// Complete snapshots are sent at most once a minute; pending retries remain due.
func (s *Store) SourceContractSnapshotDue(ctx context.Context, id string, version uint64) (bool, error) {
	at := s.now().UTC().Truncate(time.Microsecond)
	due := false
	err := s.write(ctx, func(conn *sql.Conn) error {
		j, err := s.sourceJournal(ctx, conn, id, version, at)
		if err != nil {
			return err
		}
		if j.InFlight != "" {
			return ErrSourceContractJournal
		}
		if j.SnapshotSequence == 0 {
			due = true
			return nil
		}
		if j.PendingID != "" {
			_, err = s.readSourceContractSnapshot(ctx, conn, j)
			due = err == nil
			return err
		}
		var payload, ack []byte
		var hash, protocol string
		var hv int
		err = conn.QueryRowContext(ctx, `SELECT CASE WHEN length(payload)<=32768 THEN payload ELSE NULL END,CASE WHEN length(ack_blob)<=4096 THEN ack_blob ELSE NULL END,payload_hash,protocol_revision,hash_version
   FROM outbox WHERE record_kind=? AND source_id=? AND state='acknowledged' AND json_extract(payload,'$.config_version')=? AND json_extract(payload,'$.epoch')=? AND json_extract(payload,'$.sequence')=?`, SourceContractRecordKind, id, version, j.Epoch, j.SnapshotSequence).Scan(&payload, &ack, &hash, &protocol, &hv)
		if err != nil {
			return ErrSourceContractJournal
		}
		snapshot, err := client.DecodeSourceContractTelemetry(payload)
		if err != nil || snapshot.SourceID != id || snapshot.ConfigVersion != version || snapshot.Epoch != j.Epoch || snapshot.Sequence != j.SnapshotSequence || snapshot.PayloadHash != hash || protocol != client.SourceContractSchema || hv != 1 {
			return ErrSourceContractJournal
		}
		if _, err = client.DecodeSourceContractAck(ack, snapshot, at); err != nil {
			return ErrSourceContractJournal
		}
		observed, err := time.Parse(client.SourceContractTime, snapshot.ObservedAt)
		if err != nil || at.Before(observed) {
			return ErrSourceContractJournal
		}
		due = at.Sub(observed) >= time.Minute
		return nil
	})
	return due, err
}

// PublishSourceContractSnapshot performs network I/O outside every SQLite transaction.
func (s *Store) PublishSourceContractSnapshot(ctx context.Context, api *client.Client, id string, version uint64) error {
	if api == nil {
		return ErrSourceContractJournal
	}
	due, err := s.SourceContractSnapshotDue(ctx, id, version)
	if err != nil || !due {
		return err
	}
	queued, err := s.StageSourceContractSnapshot(ctx, id, version)
	if err != nil {
		return err
	}
	ack, err := api.SubmitSourceContractSnapshot(ctx, queued.Snapshot)
	if err != nil {
		return err
	}
	return s.ApplySourceContractAck(ctx, queued, ack)
}
