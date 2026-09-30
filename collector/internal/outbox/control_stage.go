package outbox

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strconv"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

// StageControl freezes one manifest/result business and one token-specific send attempt.
// The same business can get a new attempt only after the old attempt is superseded.
func (s *Store) StageControl(ctx context.Context, kind, runID, key string, wire []byte) (client.ControlAttempt, error) {
	if kind != "manifest" && kind != "run_result" && kind != "member_action" {
		return client.ControlAttempt{}, ErrConflict
	}
	if runID == "" || key == "" || len(key) > 128 || len(wire) == 0 {
		return client.ControlAttempt{}, ErrConflict
	}
	value, err := client.ParseStrict(wire)
	if err != nil {
		return client.ControlAttempt{}, err
	}
	outer, ok := value.(map[string]any)
	if !ok || len(outer) != 2 {
		return client.ControlAttempt{}, ErrConflict
	}
	business, ok := outer["business"].(map[string]any)
	if !ok || business["run_id"] != runID {
		return client.ControlAttempt{}, ErrConflict
	}
	attempt, ok := outer["attempt"].(map[string]any)
	if !ok {
		return client.ControlAttempt{}, ErrConflict
	}
	token, ok := attempt["claim_token"].(string)
	if !ok || token == "" || len(attempt) != 2 {
		return client.ControlAttempt{}, ErrStaleRun
	}
	if revision, present := attempt["expected_run_revision"]; !present || (revision != nil && !safeCounter(revision)) {
		return client.ControlAttempt{}, ErrConflict
	}
	var identity map[string]any
	switch kind {
	case "manifest":
		part, ok := business["part_no"].(json.Number)
		if !ok {
			return client.ControlAttempt{}, ErrConflict
		}
		n, err := strconv.ParseUint(string(part), 10, 64)
		if err != nil || n > maxSafe {
			return client.ControlAttempt{}, ErrConflict
		}
		identity = map[string]any{"kind": kind, "run_id": runID, "part_no": part}
	case "run_result":
		if len(business) != 6 || !nonemptyString(business["manifest_hash"]) ||
			!safeCounter(business["part_count"]) || !safeCounter(business["expected_count"]) ||
			!nonemptyString(business["fetch_outcome"]) || business["coverage"] == nil {
			return client.ControlAttempt{}, ErrConflict
		}
		identity = map[string]any{"kind": kind, "run_id": runID}
	case "member_action":
		member, ok := business["member_id"].(string)
		if !ok || member == "" || business["action"] != "seal" || len(business) != 5 ||
			!safeCounter(business["expected_member_revision"]) || !nonemptyString(business["reason"]) {
			return client.ControlAttempt{}, ErrConflict
		}
		identity = map[string]any{"kind": kind, "run_id": runID, "member_id": member, "action": "seal"}
	}
	operation := map[string]string{"manifest": "manifest", "run_result": "result", "member_action": "member_seal"}[kind]
	businessHash, attemptHash, err := client.DecodeFrozenAttempt(wire, s.nodeID, operation)
	if err != nil {
		return client.ControlAttempt{}, err
	}
	if kind == "member_action" {
		identity["business_hash"] = businessHash
	}
	if kind == "manifest" {
		validated, err := client.DecodeManifestControl(wire, runID, s.nodeID)
		if err != nil || validated.BusinessHash != businessHash || validated.AttemptHash != attemptHash {
			return client.ControlAttempt{}, ErrConflict
		}
	}
	canonicalBusiness, err := client.CanonicalBytes(business)
	if err != nil {
		return client.ControlAttempt{}, err
	}
	recordKey := domainHash("control-record-v1", identity)
	result := client.ControlAttempt{}
	err = s.write(ctx, func(conn *sql.Conn) error {
		var currentToken []byte
		var lease int64
		var sourceID string
		if err := conn.QueryRowContext(ctx, "SELECT source_id,claim_token,lease_until FROM source_state WHERE record_kind='run' AND state_key=?", runID).Scan(&sourceID, &currentToken, &lease); err != nil {
			return err
		}
		if string(currentToken) != token || micros(s.now()) >= lease {
			return ErrStaleRun
		}
		ackConfirmed, err := currentAckConfirmed(ctx, conn, runID, token)
		if err != nil {
			return err
		}
		if !ackConfirmed {
			return ErrStaleRun
		}
		var businessID, existingHash, state string
		err = conn.QueryRowContext(ctx, "SELECT id,payload_hash,state FROM outbox WHERE record_kind=? AND record_key=?", kind, recordKey).Scan(&businessID, &existingHash, &state)
		if errors.Is(err, sql.ErrNoRows) {
			businessID, err = randomID()
			if err != nil {
				return err
			}
			_, err = conn.ExecContext(ctx, `INSERT INTO outbox(id,record_kind,record_key,source_id,run_id,payload,payload_hash,protocol_revision,hash_version,next_retry_at,created_at)
VALUES(?,?,?,?,?,?,?,'0.11',1,?,?)`, businessID, kind, recordKey, sourceID, runID, canonicalBusiness, businessHash, micros(s.now()), micros(s.now()))
			if err != nil {
				return err
			}
		} else if err != nil {
			return err
		} else if existingHash != businessHash || state != "pending" {
			return ErrConflict
		}
		var previousID, previousState, previousKey string
		var previousWire []byte
		err = conn.QueryRowContext(ctx, `SELECT a.id,a.state,a.idempotency_key,a.payload FROM outbox a WHERE a.record_kind='control_attempt' AND a.business_ref=?
AND NOT EXISTS(SELECT 1 FROM outbox newer WHERE newer.supersedes_attempt_id=a.id) LIMIT 1`, businessID).Scan(&previousID, &previousState, &previousKey, &previousWire)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if err == nil {
			if previousKey == key {
				if previousState == "superseded" {
					return ErrConflict
				}
				if !bytes.Equal(previousWire, wire) {
					return ErrConflict
				}
				result = client.ControlAttempt{ID: previousID, BusinessID: businessID, RunID: runID, Kind: kind, IdempotencyKey: key, BusinessHash: businessHash, Wire: append([]byte(nil), wire...)}
				return nil
			}
			if previousState != "superseded" {
				return ErrConflict
			}
		}
		attemptID, err := randomID()
		if err != nil {
			return err
		}
		var supersedes any
		if previousID != "" {
			supersedes = previousID
		}
		_, err = conn.ExecContext(ctx, `INSERT INTO outbox(id,record_kind,record_key,source_id,run_id,payload,payload_hash,protocol_revision,hash_version,next_retry_at,created_at,business_ref,supersedes_attempt_id,idempotency_key)
VALUES(?,'control_attempt',?,?,?,?,?,'0.11',1,?,?,?,?,?)`, attemptID, key, sourceID, runID, wire, attemptHash, micros(s.now()), micros(s.now()), businessID, supersedes, key)
		if err != nil {
			return err
		}
		result = client.ControlAttempt{ID: attemptID, BusinessID: businessID, RunID: runID, Kind: kind, IdempotencyKey: key, BusinessHash: businessHash, Wire: append([]byte(nil), wire...)}
		return nil
	})
	return result, err
}

func nonemptyString(value any) bool {
	text, ok := value.(string)
	return ok && text != ""
}

func safeCounter(value any) bool {
	number, ok := value.(json.Number)
	if !ok {
		return false
	}
	parsed, err := strconv.ParseUint(string(number), 10, 64)
	return err == nil && parsed <= maxSafe
}

func currentAckConfirmed(ctx context.Context, conn *sql.Conn, runID, token string) (bool, error) {
	rows, err := conn.QueryContext(ctx, `SELECT a.payload FROM outbox a JOIN outbox b ON b.id=a.business_ref
WHERE a.record_kind='control_attempt' AND a.state='acknowledged' AND b.record_kind='lease_action' AND b.state='acknowledged' AND a.run_id=?`, runID)
	if err != nil {
		return false, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw []byte
		if err := rows.Scan(&raw); err != nil {
			return false, err
		}
		var body map[string]map[string]any
		if json.Unmarshal(raw, &body) == nil && body["business"]["action"] == "ack" && body["attempt"]["claim_token"] == token {
			return true, nil
		}
	}
	return false, rows.Err()
}

func (s *Store) ReconcileBusiness(ctx context.Context, businessID, expectedHash, receipt string) (bool, error) {
	if businessID == "" || expectedHash == "" || receipt == "" {
		return false, ErrConflict
	}
	confirmed := false
	err := s.write(ctx, func(conn *sql.Conn) error {
		var hash, state string
		if err := conn.QueryRowContext(ctx, "SELECT payload_hash,state FROM outbox WHERE id=? AND record_kind IN ('manifest','run_result','member_action')", businessID).Scan(&hash, &state); err != nil {
			return err
		}
		if hash != expectedHash {
			return ErrConflict
		}
		if state == "acknowledged" {
			confirmed = true
			return nil
		}
		if state != "pending" {
			return ErrConflict
		}
		_, err := conn.ExecContext(ctx, "UPDATE outbox SET state='acknowledged',revision=revision+1,acknowledged_at=?,receipt_ref=? WHERE id=? AND state='pending'", micros(s.now()), receipt, businessID)
		confirmed = err == nil
		return err
	})
	return confirmed, err
}
