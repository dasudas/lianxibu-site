package outbox

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

// StageLeaseAction is a timer decision, not an HTTP retry. An unresolved action
// is returned verbatim; a completed period gets a new operation and attempt.
func (s *Store) StageLeaseAction(ctx context.Context, runID, token, kind string) (client.ControlAttempt, error) {
	if runID == "" || token == "" || (kind != "renew" && kind != "release") {
		return client.ControlAttempt{}, ErrConflict
	}
	result := client.ControlAttempt{}
	err := s.write(ctx, func(conn *sql.Conn) error {
		var raw, currentToken []byte
		var until, updated int64
		var source string
		if err := conn.QueryRowContext(ctx, "SELECT payload,source_id,claim_token,lease_until,updated_at FROM source_state WHERE record_kind='run' AND state_key=?", runID).Scan(&raw, &source, &currentToken, &until, &updated); err != nil {
			return err
		}
		now := micros(s.now())
		if now < updated {
			return ErrClockRollback
		}
		var state RunState
		if json.Unmarshal(raw, &state) != nil || state.Retention != nil || string(currentToken) != token || now >= until {
			return ErrStaleRun
		}
		confirmed, err := currentAckConfirmed(ctx, conn, runID, token)
		if err != nil {
			return err
		}
		if !confirmed {
			return ErrStaleRun
		}
		var existing client.ControlAttempt
		var action, attemptState string
		err = conn.QueryRowContext(ctx, `SELECT a.id,b.id,a.run_id,b.record_kind,a.idempotency_key,b.payload_hash,a.payload,json_extract(b.payload,'$.action'),a.state
FROM outbox b JOIN outbox a ON a.business_ref=b.id
WHERE b.record_kind='lease_action' AND b.run_id=? AND b.state IN ('pending','sending','quarantine')
AND NOT EXISTS(SELECT 1 FROM outbox n WHERE n.supersedes_attempt_id=a.id) LIMIT 1`, runID).
			Scan(&existing.ID, &existing.BusinessID, &existing.RunID, &existing.Kind, &existing.IdempotencyKey, &existing.BusinessHash, &existing.Wire, &action, &attemptState)
		if err == nil {
			if action != kind || (attemptState != "pending" && attemptState != "sending") {
				return ErrConflict
			}
			var envelope map[string]map[string]any
			if json.Unmarshal(existing.Wire, &envelope) != nil || envelope["attempt"]["claim_token"] != token {
				return ErrStaleRun
			}
			result = existing
			return nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		operationID, err := randomID()
		if err != nil {
			return err
		}
		lease := time.UnixMicro(until).UTC().Format("2006-01-02T15:04:05.000000Z")
		claim := state.Frozen
		claim.ClaimToken = &token
		claim.LeaseUntil = &lease
		claim.CurrentLeaseValid = true
		frozen, err := client.PrepareLeaseAction(kind, claim, operationID, s.now())
		if err != nil {
			return err
		}
		business, err := client.CanonicalBytes(map[string]any{"run_id": runID, "action": kind})
		if err != nil {
			return err
		}
		key := domainHash("lease-operation-v1", map[string]any{"node_id": s.nodeID, "run_id": runID, "action": kind, "operation_id": operationID})
		_, err = conn.ExecContext(ctx, `INSERT INTO outbox(id,record_kind,record_key,source_id,run_id,payload,payload_hash,protocol_revision,hash_version,next_retry_at,created_at)
VALUES(?,'lease_action',?,?,?,?,?,'0.11',1,?,?)`, operationID, key, source, runID, business, frozen.BusinessHash, now, now)
		if err != nil {
			return err
		}
		_, attemptHash, err := client.DecodeFrozenAttempt(frozen.Body, s.nodeID, "claim_"+kind)
		if err != nil {
			return err
		}
		attemptID, err := randomID()
		if err != nil {
			return err
		}
		_, err = conn.ExecContext(ctx, `INSERT INTO outbox(id,record_kind,record_key,source_id,run_id,payload,payload_hash,protocol_revision,hash_version,next_retry_at,created_at,business_ref,idempotency_key)
VALUES(?,'control_attempt',?,?,?,?,?,'0.11',1,?,?,?,?)`, attemptID, operationID, source, runID, frozen.Body, attemptHash, now, now, operationID, operationID)
		if err != nil {
			return err
		}
		result = client.ControlAttempt{ID: attemptID, BusinessID: operationID, RunID: runID, Kind: "lease_action", IdempotencyKey: operationID, BusinessHash: frozen.BusinessHash, Wire: frozen.Body}
		return nil
	})
	return result, err
}

// Called inside the same transaction as acknowledgement of the actual stored
// attempt. Historical success can be retained without granting a current lease.
func (s *Store) applyLeaseReceipt(ctx context.Context, conn *sql.Conn, runID string, wire []byte, r client.ControlResult) (bool, error) {
	if r.RunID == nil || *r.RunID != runID || r.ReceiptRef == "" {
		return false, ErrConflict
	}
	var outer map[string]map[string]any
	if json.Unmarshal(wire, &outer) != nil || outer["business"]["run_id"] != runID {
		return false, ErrConflict
	}
	token, ok := outer["attempt"]["claim_token"].(string)
	if !ok || token == "" {
		return false, ErrConflict
	}
	action, ok := outer["business"]["action"].(string)
	if !ok || (action != "ack" && action != "renew" && action != "release") {
		return false, ErrConflict
	}
	var proposed int64
	if r.LeaseUntil != nil {
		parsed, err := time.Parse("2006-01-02T15:04:05.000000Z", *r.LeaseUntil)
		if err != nil || parsed.UTC().Format("2006-01-02T15:04:05.000000Z") != *r.LeaseUntil {
			return false, ErrConflict
		}
		proposed = micros(parsed)
	}
	if r.CurrentLeaseValid && (r.LeaseUntil == nil || proposed <= micros(s.now()) || action == "release") {
		return false, ErrConflict
	}
	var currentToken []byte
	var until, updated int64
	if err := conn.QueryRowContext(ctx, "SELECT claim_token,lease_until,updated_at FROM source_state WHERE record_kind='run' AND state_key=?", runID).Scan(&currentToken, &until, &updated); err != nil {
		return false, err
	}
	if string(currentToken) != token {
		return false, nil
	}
	now := micros(s.now())
	if now < updated {
		return false, ErrClockRollback
	}
	if action == "release" || !r.CurrentLeaseValid {
		until = now
	} else if proposed > until {
		until = proposed
	} else {
		return true, nil
	}
	_, err := conn.ExecContext(ctx, "UPDATE source_state SET lease_until=?,updated_at=? WHERE record_kind='run' AND state_key=? AND claim_token=?", until, now, runID, currentToken)
	return err == nil, err
}
