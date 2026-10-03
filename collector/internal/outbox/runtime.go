package outbox

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

// ActiveClaims restores only live leases whose persisted ACK has been confirmed.
func (s *Store) ActiveClaims(ctx context.Context) ([]client.RunClaim, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT payload,claim_token,lease_until FROM source_state
 WHERE record_kind='run' AND lease_until>? ORDER BY updated_at,state_key LIMIT 10`, micros(s.now()))
	if err != nil {
		return nil, err
	}
	candidates := []client.RunClaim{}
	for rows.Next() {
		var raw, token []byte
		var until int64
		var state RunState
		if err = rows.Scan(&raw, &token, &until); err != nil {
			rows.Close()
			return nil, err
		}
		if json.Unmarshal(raw, &state) != nil {
			rows.Close()
			return nil, ErrConflict
		}
		if state.Retention != nil {
			continue
		}
		claim := state.Frozen
		t := string(token)
		u := time.UnixMicro(until).UTC().Format("2006-01-02T15:04:05.000000Z")
		claim.ClaimToken = &t
		claim.LeaseUntil = &u
		claim.CurrentLeaseValid = true
		candidates = append(candidates, claim)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	result := []client.RunClaim{}
	conn, err := s.db.Conn(ctx)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	for _, c := range candidates {
		confirmed, e := currentAckConfirmed(ctx, conn, c.Run.RunID, *c.ClaimToken)
		if e != nil {
			return nil, e
		}
		if confirmed {
			result = append(result, c)
		}
	}
	return result, nil
}

// StageCompletedRun builds bounded manifest parts from the immutable local observations.
// Stable attempt identities allow interruption between parts and lease replacement.
func (s *Store) StageCompletedRun(ctx context.Context, claim client.RunClaim, resultRevision uint64) error {
	state, _, err := s.Run(ctx, claim.Run.RunID)
	if err != nil {
		return err
	}
	if !state.FetchComplete || claim.ClaimToken == nil {
		return ErrStaleRun
	}
	parts := []any{}
	count := 0
	cursor := ""
	for partNo := 0; partNo < 10000; partNo++ {
		rows, err := s.db.QueryContext(ctx, `SELECT member_id,observation_id,payload_hash,replaces_observation_id FROM outbox
   WHERE record_kind='observation' AND run_id=? AND member_id>? ORDER BY member_id LIMIT 10`, claim.Run.RunID, cursor)
		if err != nil {
			return err
		}
		members := []any{}
		for rows.Next() {
			var member, observation, hash string
			var replaces sql.NullString
			if err = rows.Scan(&member, &observation, &hash, &replaces); err != nil {
				rows.Close()
				return err
			}
			if member <= cursor {
				rows.Close()
				return ErrConflict
			}
			cursor = member
			var replacement any
			if replaces.Valid {
				replacement = replaces.String
			}
			members = append(members, map[string]any{"member_id": member, "source_item_id": nil, "observation_id": observation, "payload_hash": hash, "replaces_observation_id": replacement})
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		if len(members) == 0 {
			break
		}
		hash, err := client.Hash(client.ManifestPartHash, map[string]any{"run_id": claim.Run.RunID, "part_no": partNo, "members": members})
		if err != nil {
			return err
		}
		business := map[string]any{"run_id": claim.Run.RunID, "part_no": partNo, "part_hash": hash, "members": members}
		if err = s.stageRuntimeControl(ctx, claim, "manifest", business, nil); err != nil {
			return err
		}
		parts = append(parts, map[string]any{"part_no": partNo, "part_hash": hash, "member_count": len(members)})
		count += len(members)
	}
	// Never turn a capped traversal into a successful truncated manifest.
	total, err := s.ObservationCount(ctx, claim.Run.RunID)
	if err != nil {
		return err
	}
	if total != count {
		return ErrConflict
	}
	var pending int
	if err = s.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM outbox WHERE record_kind='manifest' AND run_id=? AND state!='acknowledged'", claim.Run.RunID).Scan(&pending); err != nil {
		return err
	}
	if pending > 0 {
		return nil
	}
	hash, err := client.Hash(client.ManifestHash, map[string]any{"run_id": claim.Run.RunID, "expected_count": count, "parts": parts})
	if err != nil {
		return err
	}
	outcome := "succeeded"
	if len(state.CoverageGaps) > 0 {
		outcome = "partial"
	}
	missing := make([]any, len(state.CoverageGaps))
	for i, gap := range state.CoverageGaps {
		missing[i] = map[string]any{"kind": "source_run", "id": claim.Run.RunID, "business_date": claim.Run.BusinessDate, "reason": gap}
	}
	coverageState := "complete"
	if len(missing) > 0 {
		coverageState = "partial"
	}
	noUpdates := []any{}
	if count == 0 && len(missing) == 0 && claim.Run.FetchMode == "daily_delta" {
		noUpdates = append(noUpdates, claim.Run.BusinessDate)
	}
	return s.stageRuntimeControl(ctx, claim, "run_result", map[string]any{"run_id": claim.Run.RunID, "manifest_hash": hash,
		"expected_count": count, "part_count": len(parts), "fetch_outcome": outcome, "coverage": map[string]any{"state": coverageState, "missing_inputs": missing, "missing_dates": []any{}, "no_update_dates": noUpdates}}, resultRevision)
}

func (s *Store) stageRuntimeControl(ctx context.Context, claim client.RunClaim, kind string, business map[string]any, revision any) error {
	operation := kind
	if kind == "run_result" {
		operation = "result"
	}
	hash, err := client.Hash(client.ControlBusinessHash, map[string]any{"operation": operation, "business": business})
	if err != nil {
		return err
	}
	var status string
	err = s.db.QueryRowContext(ctx, "SELECT state FROM outbox WHERE record_kind=? AND run_id=? AND payload_hash=?", kind, claim.Run.RunID, hash).Scan(&status)
	if err == nil && status == "acknowledged" {
		return nil
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	var active int
	err = s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM outbox a JOIN outbox b ON a.business_ref=b.id
 WHERE b.record_kind=? AND b.run_id=? AND b.payload_hash=? AND a.state IN ('pending','sending')
 AND NOT EXISTS(SELECT 1 FROM outbox n WHERE n.supersedes_attempt_id=a.id)`, kind, claim.Run.RunID, hash).Scan(&active)
	if err != nil {
		return err
	}
	if active > 0 {
		return nil
	}
	wire, err := json.Marshal(map[string]any{"business": business, "attempt": map[string]any{"claim_token": *claim.ClaimToken, "expected_run_revision": revision}})
	if err != nil {
		return err
	}
	key, err := client.Hash(client.ControlAttemptHash, map[string]any{"node_id": s.nodeID, "operation": operation, "business_hash": hash,
		"attempt": map[string]any{"claim_token": *claim.ClaimToken, "expected_run_revision": revision}})
	if err != nil {
		return err
	}
	_, err = s.StageControl(ctx, kind, claim.Run.RunID, key, wire)
	return err
}

func (s *Store) ResultAcknowledged(ctx context.Context, runID string) (bool, error) {
	var count int
	err := s.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM outbox WHERE record_kind='run_result' AND run_id=? AND state='acknowledged'", runID).Scan(&count)
	return count == 1, err
}

// CompletedRuns supports bounded keyset scanning, including leases already released.
func (s *Store) CompletedRuns(ctx context.Context, after string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT run_id FROM outbox WHERE record_kind='run_result' AND state='acknowledged' AND run_id>? ORDER BY run_id LIMIT 10`, after)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
