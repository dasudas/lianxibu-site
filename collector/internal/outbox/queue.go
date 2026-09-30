package outbox

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

func (s *Store) NextObservationBatch(ctx context.Context, limit, maxBytes int) ([]client.FrozenObservation, error) {
	if limit < 1 || limit > 10 || maxBytes < 1 || maxBytes > 512<<10 {
		return nil, errors.New("invalid observation batch bound")
	}
	var batch []client.FrozenObservation
	err := s.write(ctx, func(conn *sql.Conn) error {
		rows, err := conn.QueryContext(ctx, `SELECT id,run_id,member_id,observation_id,payload,payload_hash
FROM outbox WHERE record_kind='observation' AND state='pending' AND next_retry_at<=?
AND NOT EXISTS(SELECT 1 FROM source_state s WHERE s.record_kind='run' AND s.state_key=outbox.run_id AND json_extract(s.payload,'$.retention') IS NOT NULL)
ORDER BY id LIMIT ?`, micros(s.now()), limit)
		if err != nil {
			return err
		}
		used := len(`{"observations":[]}`)
		for rows.Next() {
			var r client.FrozenObservation
			if err := rows.Scan(&r.RecordID, &r.RunID, &r.MemberID, &r.ObservationID, &r.Wire, &r.PayloadHash); err != nil {
				rows.Close()
				return err
			}
			if used+len(r.Wire)+1 > maxBytes {
				if len(batch) == 0 {
					rows.Close()
					return ErrConflict
				}
				break
			}
			used += len(r.Wire) + 1
			batch = append(batch, r)
		}
		if err := rows.Err(); err != nil {
			rows.Close()
			return err
		}
		rows.Close()
		for _, r := range batch {
			result, err := conn.ExecContext(ctx, "UPDATE outbox SET state='sending',revision=revision+1,send_attempts=send_attempts+1 WHERE id=? AND state='pending'", r.RecordID)
			if err != nil {
				return err
			}
			changed, _ := result.RowsAffected()
			if changed != 1 {
				return ErrConflict
			}
		}
		return nil
	})
	return batch, err
}

func (s *Store) RetryObservations(ctx context.Context, batch []client.FrozenObservation) error {
	return s.write(ctx, func(conn *sql.Conn) error {
		for _, item := range batch {
			if _, err := conn.ExecContext(ctx, "UPDATE outbox SET state='pending',revision=revision+1,next_retry_at=? WHERE id=? AND state='sending'", micros(s.now().Add(5*time.Second)), item.RecordID); err != nil {
				return err
			}
		}
		return nil
	})
}

func (s *Store) ApplyObservationAcks(ctx context.Context, batch []client.FrozenObservation, acks []client.ObservationAck) error {
	if len(batch) != len(acks) {
		return ErrConflict
	}
	byID := map[string]client.ObservationAck{}
	for _, ack := range acks {
		if _, exists := byID[ack.ObservationID]; exists {
			return ErrConflict
		}
		byID[ack.ObservationID] = ack
	}
	return s.write(ctx, func(conn *sql.Conn) error {
		for _, item := range batch {
			ack, ok := byID[item.ObservationID]
			if !ok || ack.MemberID != item.MemberID {
				return ErrConflict
			}
			var state, member, hash string
			if err := conn.QueryRowContext(ctx, "SELECT state,member_id,payload_hash FROM outbox WHERE id=? AND observation_id=?", item.RecordID, item.ObservationID).Scan(&state, &member, &hash); err != nil {
				return err
			}
			if state != "sending" || member != item.MemberID || hash != item.PayloadHash {
				return ErrConflict
			}
			status := "pending"
			retry := micros(s.now().Add(10 * time.Second))
			var at any
			var receipt any
			switch ack.Status {
			case "accepted", "duplicate":
				if ack.ReceiptRef == nil || *ack.ReceiptRef == "" || ack.AcceptedAt == nil {
					return ErrConflict
				}
				status = "acknowledged"
				at = micros(s.now())
				receipt = *ack.ReceiptRef
				retry = micros(s.now())
			case "rejected", "blocked":
				status = "quarantine"
			case "retryable":
			default:
				return ErrConflict
			}
			blob, err := json.Marshal(ack)
			if err != nil {
				return err
			}
			_, err = conn.ExecContext(ctx, "UPDATE outbox SET state=?,revision=revision+1,next_retry_at=?,acknowledged_at=?,receipt_ref=?,ack_blob=? WHERE id=? AND state='sending'", status, retry, at, receipt, blob, item.RecordID)
			if err != nil {
				return err
			}
		}
		return nil
	})
}

func (s *Store) NextControlAttempt(ctx context.Context) (*client.ControlAttempt, error) {
	var attempt *client.ControlAttempt
	err := s.write(ctx, func(conn *sql.Conn) error {
		row := conn.QueryRowContext(ctx, `SELECT a.id,a.business_ref,a.run_id,b.record_kind,a.idempotency_key,b.payload_hash,a.payload
FROM outbox a JOIN outbox b ON b.id=a.business_ref
WHERE a.record_kind='control_attempt' AND a.state='pending' AND b.state='pending' AND a.next_retry_at<=?
AND NOT EXISTS(SELECT 1 FROM outbox newer WHERE newer.supersedes_attempt_id=a.id AND newer.state!='superseded')
AND (b.record_kind!='run_result' OR NOT EXISTS(SELECT 1 FROM outbox part WHERE part.run_id=b.run_id AND part.record_kind='manifest' AND part.state!='acknowledged'))
ORDER BY CASE b.record_kind WHEN 'lease_action' THEN 0 WHEN 'member_action' THEN 1 WHEN 'manifest' THEN 2 WHEN 'run_result' THEN 3 ELSE 4 END,a.id LIMIT 1`, micros(s.now()))
		a := client.ControlAttempt{}
		if err := row.Scan(&a.ID, &a.BusinessID, &a.RunID, &a.Kind, &a.IdempotencyKey, &a.BusinessHash, &a.Wire); errors.Is(err, sql.ErrNoRows) {
			return nil
		} else if err != nil {
			return err
		}
		result, err := conn.ExecContext(ctx, "UPDATE outbox SET state='sending',revision=revision+1,send_attempts=send_attempts+1 WHERE id=? AND state='pending'", a.ID)
		if err != nil {
			return err
		}
		changed, _ := result.RowsAffected()
		if changed != 1 {
			return ErrConflict
		}
		attempt = &a
		return nil
	})
	return attempt, err
}

func (s *Store) RetryControlAttempt(ctx context.Context, a client.ControlAttempt) error {
	return s.write(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, "UPDATE outbox SET state='pending',revision=revision+1,next_retry_at=? WHERE id=? AND business_ref=? AND state='sending'", micros(s.now().Add(5*time.Second)), a.ID, a.BusinessID)
		return err
	})
}

func (s *Store) ApplyControlReceipt(ctx context.Context, a client.ControlAttempt, r client.ControlResult) (bool, error) {
	applied := false
	err := s.write(ctx, func(conn *sql.Conn) error {
		var state, businessState, hash string
		err := conn.QueryRowContext(ctx, `SELECT a.state,b.state,b.payload_hash FROM outbox a JOIN outbox b ON b.id=a.business_ref
WHERE a.id=? AND a.business_ref=? AND a.idempotency_key=?`, a.ID, a.BusinessID, a.IdempotencyKey).Scan(&state, &businessState, &hash)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if state != "sending" || businessState != "pending" {
			return nil
		}
		if hash != a.BusinessHash || hash != r.BusinessHash {
			return ErrConflict
		}
		var newer int
		if err := conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM outbox WHERE supersedes_attempt_id=? AND state!='superseded'", a.ID).Scan(&newer); err != nil {
			return err
		}
		if newer > 0 {
			return nil
		}
		blob, err := json.Marshal(r)
		if err != nil {
			return err
		}
		_, err = conn.ExecContext(ctx, "UPDATE outbox SET state='acknowledged',revision=revision+1,acknowledged_at=?,receipt_ref=?,ack_blob=? WHERE id=? AND state='sending'", micros(s.now()), r.ReceiptRef, blob, a.ID)
		if err != nil {
			return err
		}
		_, err = conn.ExecContext(ctx, "UPDATE outbox SET state='acknowledged',revision=revision+1,acknowledged_at=?,receipt_ref=?,ack_blob=? WHERE id=? AND state='pending'", micros(s.now()), r.ReceiptRef, blob, a.BusinessID)
		if err != nil {
			return err
		}
		applied = true
		return nil
	})
	return applied, err
}

func (s *Store) StopControlAttempt(ctx context.Context, a client.ControlAttempt, code string) error {
	return s.write(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, "UPDATE outbox SET state='superseded',revision=revision+1,last_error_code=? WHERE id=? AND business_ref=? AND state='sending'", code, a.ID, a.BusinessID)
		return err
	})
}

func (s *Store) QuarantineControl(ctx context.Context, a client.ControlAttempt, code string) error {
	return s.write(ctx, func(conn *sql.Conn) error {
		if _, err := conn.ExecContext(ctx, "UPDATE outbox SET state='quarantine',revision=revision+1,last_error_code=? WHERE id=? AND business_ref=? AND state='sending'", code, a.ID, a.BusinessID); err != nil {
			return err
		}
		_, err := conn.ExecContext(ctx, "UPDATE outbox SET state='quarantine',revision=revision+1,last_error_code=? WHERE id=? AND state='pending'", code, a.BusinessID)
		return err
	})
}
