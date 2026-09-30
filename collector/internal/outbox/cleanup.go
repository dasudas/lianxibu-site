package outbox

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"sort"
	"strconv"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

var ErrRetentionHold = errors.New("run retention conditions are not met")
var ErrClockRollback = errors.New("collector clock moved backward")

type RetentionMark struct {
	SchemaVersion         string `json:"schema_version"`
	TerminalConfirmedAt   int64  `json:"terminal_confirmed_at"`
	VerifiedManifestHash  string `json:"verified_manifest_hash"`
	VerifiedPartCount     uint64 `json:"verified_part_count"`
	VerifiedExpectedCount uint64 `json:"verified_expected_count"`
	CleanupNotBefore      int64  `json:"cleanup_not_before"`
	CleanupState          string `json:"cleanup_state"`
}

type TerminalSnapshot struct {
	RunID              string
	DeliveryState      string
	ManifestHash       string
	ResultBusinessHash string
	PartCount          uint64
	ExpectedCount      uint64
	AcceptedCount      uint64
	ExcludedCount      uint64
	PendingCount       uint64
	Parts              []json.RawMessage
}

// TerminalReader supplies a fresh server readback; network failure leaves all rows intact.
type TerminalReader interface {
	ReadTerminal(context.Context, string) (TerminalSnapshot, error)
}

type verifiedMember struct {
	ObservationID         string
	PayloadHash           string
	ReplacesObservationID string
}

func verifyRemoteManifest(snapshot TerminalSnapshot) (map[string]verifiedMember, map[uint64]string, error) {
	if snapshot.RunID == "" || !validDigest(snapshot.ManifestHash) || !validDigest(snapshot.ResultBusinessHash) ||
		snapshot.PartCount > maxSafe || snapshot.ExpectedCount > maxSafe || snapshot.PartCount != uint64(len(snapshot.Parts)) ||
		snapshot.AcceptedCount > snapshot.ExpectedCount || snapshot.ExcludedCount != snapshot.ExpectedCount-snapshot.AcceptedCount || snapshot.PendingCount != 0 {
		return nil, nil, ErrRetentionHold
	}
	switch snapshot.DeliveryState {
	case "complete", "partial", "no_usable_input", "empty_success":
	default:
		return nil, nil, ErrRetentionHold
	}
	members := map[string]verifiedMember{}
	parts := map[uint64]string{}
	rootParts := make([]any, 0, len(snapshot.Parts))
	for i, raw := range snapshot.Parts {
		parsed, err := client.ParseStrict(raw)
		if err != nil {
			return nil, nil, err
		}
		part, ok := parsed.(map[string]any)
		if !ok || len(part) != 4 || part["run_id"] != snapshot.RunID {
			return nil, nil, ErrRetentionHold
		}
		partNo, ok := parseSafe(part["part_no"])
		if !ok || partNo != uint64(i) {
			return nil, nil, ErrRetentionHold
		}
		listed, ok := part["members"].([]any)
		if !ok || len(listed) > 100 {
			return nil, nil, ErrRetentionHold
		}
		partHash, ok := part["part_hash"].(string)
		if !ok || !validDigest(partHash) {
			return nil, nil, ErrRetentionHold
		}
		calculated, err := client.Hash(client.ManifestPartHash, map[string]any{"run_id": snapshot.RunID, "part_no": part["part_no"], "members": listed})
		if err != nil || calculated != partHash {
			return nil, nil, ErrRetentionHold
		}
		lastMember := ""
		for _, entry := range listed {
			member, ok := entry.(map[string]any)
			if !ok || len(member) != 5 {
				return nil, nil, ErrRetentionHold
			}
			id, ok := member["member_id"].(string)
			observation, obsOK := member["observation_id"].(string)
			hash, hashOK := member["payload_hash"].(string)
			if !ok || id == "" || id <= lastMember || !obsOK || observation == "" || !hashOK || !validDigest(hash) {
				return nil, nil, ErrRetentionHold
			}
			if _, exists := members[id]; exists {
				return nil, nil, ErrRetentionHold
			}
			replaces := ""
			if member["replaces_observation_id"] != nil {
				var valid bool
				replaces, valid = member["replaces_observation_id"].(string)
				if !valid || replaces == "" || replaces == observation {
					return nil, nil, ErrRetentionHold
				}
			}
			members[id] = verifiedMember{ObservationID: observation, PayloadHash: hash, ReplacesObservationID: replaces}
			lastMember = id
		}
		businessHash, err := client.Hash(client.ControlBusinessHash, map[string]any{"operation": "manifest", "business": part})
		if err != nil {
			return nil, nil, err
		}
		parts[partNo] = businessHash
		rootParts = append(rootParts, map[string]any{"part_no": part["part_no"], "part_hash": partHash, "member_count": len(listed)})
	}
	if uint64(len(members)) != snapshot.ExpectedCount {
		return nil, nil, ErrRetentionHold
	}
	root, err := client.Hash(client.ManifestHash, map[string]any{"run_id": snapshot.RunID, "expected_count": snapshot.ExpectedCount, "parts": rootParts})
	if err != nil || root != snapshot.ManifestHash {
		return nil, nil, ErrRetentionHold
	}
	return members, parts, nil
}

func parseSafe(value any) (uint64, bool) {
	n, ok := value.(json.Number)
	if !ok {
		return 0, false
	}
	v, err := strconv.ParseUint(string(n), 10, 64)
	return v, err == nil && v <= maxSafe
}

// ConfirmTerminal verifies a fresh remote snapshot against immutable local evidence
// and writes the seven-day clock in the same SQLite transaction as the terminal mark.
func (s *Store) ConfirmTerminal(ctx context.Context, runID string, reader TerminalReader) error {
	if runID == "" || reader == nil {
		return ErrRetentionHold
	}
	snapshot, err := reader.ReadTerminal(ctx, runID)
	if err != nil {
		return err
	}
	if snapshot.RunID != runID {
		return ErrRetentionHold
	}
	members, parts, err := verifyRemoteManifest(snapshot)
	if err != nil {
		return err
	}
	return s.write(ctx, func(conn *sql.Conn) error {
		var raw []byte
		var revision uint64
		var updatedAt int64
		if err := conn.QueryRowContext(ctx, "SELECT payload,revision,updated_at FROM source_state WHERE record_kind='run' AND state_key=?", runID).Scan(&raw, &revision, &updatedAt); err != nil {
			return err
		}
		var state RunState
		if json.Unmarshal(raw, &state) != nil || state.Frozen.Run.RunID != runID {
			return ErrRetentionHold
		}
		if state.Retention != nil {
			if state.Retention.VerifiedManifestHash == snapshot.ManifestHash && state.Retention.VerifiedPartCount == snapshot.PartCount && state.Retention.VerifiedExpectedCount == snapshot.ExpectedCount {
				return nil
			}
			return ErrConflict
		}
		now := micros(s.now())
		if now < updatedAt {
			return ErrClockRollback
		}
		if err := verifyLocalResult(ctx, conn, runID, snapshot.ResultBusinessHash, snapshot.ManifestHash, snapshot.PartCount, snapshot.ExpectedCount); err != nil {
			return err
		}
		rows, err := conn.QueryContext(ctx, "SELECT payload,payload_hash,state,receipt_ref FROM outbox WHERE record_kind='manifest' AND run_id=?", runID)
		if err != nil {
			return err
		}
		seenParts := map[uint64]bool{}
		for rows.Next() {
			var payload []byte
			var hash, state string
			var receipt sql.NullString
			if err := rows.Scan(&payload, &hash, &state, &receipt); err != nil {
				rows.Close()
				return err
			}
			parsed, err := client.ParseStrict(payload)
			if err != nil {
				rows.Close()
				return err
			}
			business, ok := parsed.(map[string]any)
			if !ok || business["run_id"] != runID {
				rows.Close()
				return ErrRetentionHold
			}
			partNo, ok := parseSafe(business["part_no"])
			if !ok || seenParts[partNo] || state != "acknowledged" || !receipt.Valid || receipt.String == "" || parts[partNo] != hash {
				rows.Close()
				return ErrRetentionHold
			}
			remote, err := client.CanonicalBytes(business)
			if err != nil || !bytes.Equal(remote, payload) {
				rows.Close()
				return ErrRetentionHold
			}
			seenParts[partNo] = true
		}
		if err := rows.Err(); err != nil {
			rows.Close()
			return err
		}
		rows.Close()
		if len(seenParts) != len(parts) {
			return ErrRetentionHold
		}
		sealed := map[string]bool{}
		sealRows, err := conn.QueryContext(ctx, "SELECT payload FROM outbox WHERE record_kind='member_action' AND run_id=? AND state='acknowledged' AND receipt_ref IS NOT NULL", runID)
		if err != nil {
			return err
		}
		for sealRows.Next() {
			var payload []byte
			if err := sealRows.Scan(&payload); err != nil {
				sealRows.Close()
				return err
			}
			var business map[string]any
			if json.Unmarshal(payload, &business) != nil || business["run_id"] != runID || business["action"] != "seal" {
				sealRows.Close()
				return ErrRetentionHold
			}
			memberID, ok := business["member_id"].(string)
			if !ok || memberID == "" {
				sealRows.Close()
				return ErrRetentionHold
			}
			sealed[memberID] = true
		}
		if err := sealRows.Err(); err != nil {
			sealRows.Close()
			return err
		}
		sealRows.Close()
		obsRows, err := conn.QueryContext(ctx, "SELECT observation_id,member_id,payload_hash,state,receipt_ref,ack_blob,replaces_observation_id FROM outbox WHERE record_kind='observation' AND run_id=?", runID)
		if err != nil {
			return err
		}
		type localObservation struct {
			ID, Hash, Status, Replaces string
			Receipt                    sql.NullString
			Ack                        client.ObservationAck
		}
		local := map[string]map[string]localObservation{}
		for obsRows.Next() {
			var obsID, memberID, hash, status string
			var receipt, replaces sql.NullString
			var ack []byte
			if err := obsRows.Scan(&obsID, &memberID, &hash, &status, &receipt, &ack, &replaces); err != nil {
				obsRows.Close()
				return err
			}
			if _, exists := members[memberID]; !exists || obsID == "" || !validDigest(hash) {
				obsRows.Close()
				return ErrRetentionHold
			}
			var response client.ObservationAck
			if len(ack) > 0 && json.Unmarshal(ack, &response) != nil {
				obsRows.Close()
				return ErrRetentionHold
			}
			if local[memberID] == nil {
				local[memberID] = map[string]localObservation{}
			}
			if _, exists := local[memberID][obsID]; exists {
				obsRows.Close()
				return ErrRetentionHold
			}
			local[memberID][obsID] = localObservation{ID: obsID, Hash: hash, Status: status, Replaces: replaces.String, Receipt: receipt, Ack: response}
		}
		if err := obsRows.Err(); err != nil {
			obsRows.Close()
			return err
		}
		obsRows.Close()
		accepted := uint64(0)
		for memberID, member := range members {
			rows := local[memberID]
			current, exists := rows[member.ObservationID]
			if !exists || current.Hash != member.PayloadHash || current.Replaces != member.ReplacesObservationID {
				return ErrRetentionHold
			}
			for id, visited := member.ObservationID, map[string]bool{}; id != ""; {
				row, exists := rows[id]
				if !exists || visited[id] {
					return ErrRetentionHold
				}
				visited[id] = true
				if row.Status == "pending" || row.Status == "sending" {
					return ErrRetentionHold
				}
				if row.Status == "acknowledged" {
					response := row.Ack
					if !row.Receipt.Valid || row.Receipt.String == "" || response.ObservationID != id || response.MemberID != memberID ||
						(response.Status != "accepted" && response.Status != "duplicate") || response.ReceiptRef == nil || *response.ReceiptRef != row.Receipt.String || !validAcceptedTime(response.AcceptedAt) {
						return ErrRetentionHold
					}
				}
				id = row.Replaces
				if id == "" {
					if len(visited) != len(rows) {
						return ErrRetentionHold
					}
				}
			}
			response := current.Ack
			status := current.Status
			obsID := current.ID
			if status == "acknowledged" {
				accepted++
			} else if status == "quarantine" || status == "sealed" {
				rejected := response.Status == "rejected" && response.Code != nil && *response.Code != "" && response.ObservationID == obsID && response.MemberID == memberID
				if !rejected && !sealed[memberID] {
					return ErrRetentionHold
				}
			} else {
				return ErrRetentionHold
			}
		}
		if len(local) != len(members) || accepted != snapshot.AcceptedCount {
			return ErrRetentionHold
		}
		state.Retention = &RetentionMark{SchemaVersion: "1", TerminalConfirmedAt: now, VerifiedManifestHash: snapshot.ManifestHash,
			VerifiedPartCount: snapshot.PartCount, VerifiedExpectedCount: snapshot.ExpectedCount,
			CleanupNotBefore: now + int64((7*24*time.Hour)/time.Microsecond), CleanupState: "waiting"}
		encoded, err := json.Marshal(state)
		if err != nil {
			return err
		}
		change, err := conn.ExecContext(ctx, "UPDATE source_state SET payload=?,revision=revision+1,updated_at=? WHERE record_kind='run' AND state_key=? AND revision=?", encoded, now, runID, revision)
		if err != nil {
			return err
		}
		count, err := change.RowsAffected()
		if err != nil || count != 1 {
			return ErrConflict
		}
		return nil
	})
}

// CleanupConfirmedObservations deletes at most 100 fully acknowledged rows.
func (s *Store) CleanupConfirmedObservations(ctx context.Context, runID string, limit int) (int, error) {
	if runID == "" || limit < 1 || limit > 100 {
		return 0, ErrConflict
	}
	deleted := 0
	err := s.write(ctx, func(conn *sql.Conn) error {
		var raw []byte
		var revision uint64
		var updatedAt int64
		if err := conn.QueryRowContext(ctx, "SELECT payload,revision,updated_at FROM source_state WHERE record_kind='run' AND state_key=?", runID).Scan(&raw, &revision, &updatedAt); err != nil {
			return err
		}
		var state RunState
		if json.Unmarshal(raw, &state) != nil || state.Retention == nil || state.Retention.SchemaVersion != "1" {
			return ErrRetentionHold
		}
		mark := state.Retention
		now := micros(s.now())
		if now < updatedAt || now < mark.TerminalConfirmedAt {
			return ErrClockRollback
		}
		if now < mark.CleanupNotBefore {
			return ErrRetentionHold
		}
		if mark.CleanupState == "done" {
			return nil
		}
		var resultHash string
		if err := conn.QueryRowContext(ctx, "SELECT payload_hash FROM outbox WHERE record_kind='run_result' AND run_id=? AND state='acknowledged' AND receipt_ref IS NOT NULL", runID).Scan(&resultHash); err != nil {
			return ErrRetentionHold
		}
		if err := verifyLocalResult(ctx, conn, runID, resultHash, mark.VerifiedManifestHash, mark.VerifiedPartCount, mark.VerifiedExpectedCount); err != nil {
			return err
		}
		root, partCount, err := localManifestRoot(ctx, conn, runID, mark.VerifiedExpectedCount)
		if err != nil || root != mark.VerifiedManifestHash || partCount != mark.VerifiedPartCount {
			return ErrRetentionHold
		}
		var pending int
		if err := conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM outbox WHERE record_kind='observation' AND run_id=? AND state IN ('pending','sending')", runID).Scan(&pending); err != nil {
			return err
		}
		if pending != 0 {
			return ErrRetentionHold
		}
		ids := []string{}
		rows, err := conn.QueryContext(ctx, "SELECT id,observation_id,member_id,receipt_ref,ack_blob FROM outbox WHERE record_kind='observation' AND run_id=? AND state='acknowledged' AND receipt_ref IS NOT NULL AND ack_blob IS NOT NULL ORDER BY id LIMIT ?", runID, limit)
		if err != nil {
			return err
		}
		for rows.Next() {
			var id, observationID, memberID, receipt string
			var ack []byte
			if err := rows.Scan(&id, &observationID, &memberID, &receipt, &ack); err != nil {
				rows.Close()
				return err
			}
			var response client.ObservationAck
			if json.Unmarshal(ack, &response) != nil || response.ObservationID != observationID || response.MemberID != memberID ||
				(response.Status != "accepted" && response.Status != "duplicate") || response.ReceiptRef == nil || *response.ReceiptRef != receipt || !validAcceptedTime(response.AcceptedAt) {
				rows.Close()
				return ErrRetentionHold
			}
			ids = append(ids, id)
		}
		if err := rows.Err(); err != nil {
			rows.Close()
			return err
		}
		rows.Close()
		for _, id := range ids {
			if _, err := conn.ExecContext(ctx, "DELETE FROM outbox WHERE id=? AND record_kind='observation' AND state='acknowledged' AND receipt_ref IS NOT NULL AND ack_blob IS NOT NULL", id); err != nil {
				return err
			}
		}
		deleted = len(ids)
		var remaining int
		if err := conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM outbox WHERE record_kind='observation' AND run_id=? AND state='acknowledged'", runID).Scan(&remaining); err != nil {
			return err
		}
		if remaining == 0 {
			mark.CleanupState = "done"
		} else {
			mark.CleanupState = "partial"
		}
		encoded, err := json.Marshal(state)
		if err != nil {
			return err
		}
		change, err := conn.ExecContext(ctx, "UPDATE source_state SET payload=?,revision=revision+1,updated_at=? WHERE record_kind='run' AND state_key=? AND revision=?", encoded, now, runID, revision)
		if err != nil {
			return err
		}
		count, err := change.RowsAffected()
		if err != nil || count != 1 {
			return ErrConflict
		}
		return nil
	})
	return deleted, err
}

func verifyLocalResult(ctx context.Context, conn *sql.Conn, runID, resultHash, manifestHash string, partCount, expectedCount uint64) error {
	var payload []byte
	var count int
	if err := conn.QueryRowContext(ctx, "SELECT payload FROM outbox WHERE record_kind='run_result' AND run_id=? AND state='acknowledged' AND payload_hash=? AND receipt_ref IS NOT NULL", runID, resultHash).Scan(&payload); err != nil {
		return ErrRetentionHold
	}
	if err := conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM outbox WHERE record_kind='run_result' AND run_id=?", runID).Scan(&count); err != nil || count != 1 {
		return ErrRetentionHold
	}
	parsed, err := client.ParseStrict(payload)
	if err != nil {
		return err
	}
	business, ok := parsed.(map[string]any)
	if !ok || business["run_id"] != runID || business["manifest_hash"] != manifestHash {
		return ErrRetentionHold
	}
	pc, ok := parseSafe(business["part_count"])
	if !ok || pc != partCount {
		return ErrRetentionHold
	}
	ec, ok := parseSafe(business["expected_count"])
	if !ok || ec != expectedCount {
		return ErrRetentionHold
	}
	calculated, err := client.Hash(client.ControlBusinessHash, map[string]any{"operation": "result", "business": business})
	if err != nil || calculated != resultHash {
		return ErrRetentionHold
	}
	return nil
}

func localManifestRoot(ctx context.Context, conn *sql.Conn, runID string, expectedCount uint64) (string, uint64, error) {
	rows, err := conn.QueryContext(ctx, "SELECT payload,payload_hash,state,receipt_ref FROM outbox WHERE record_kind='manifest' AND run_id=?", runID)
	if err != nil {
		return "", 0, err
	}
	type part struct {
		Number uint64
		Hash   string
		Count  int
	}
	parts := []part{}
	for rows.Next() {
		var payload []byte
		var hash, status string
		var receipt sql.NullString
		if err := rows.Scan(&payload, &hash, &status, &receipt); err != nil {
			rows.Close()
			return "", 0, err
		}
		parsed, err := client.ParseStrict(payload)
		if err != nil {
			rows.Close()
			return "", 0, err
		}
		business, ok := parsed.(map[string]any)
		if !ok || business["run_id"] != runID || status != "acknowledged" || !receipt.Valid || receipt.String == "" {
			rows.Close()
			return "", 0, ErrRetentionHold
		}
		number, ok := parseSafe(business["part_no"])
		members, hasMembers := business["members"].([]any)
		partHash, hasHash := business["part_hash"].(string)
		if !ok || !hasMembers || !hasHash {
			rows.Close()
			return "", 0, ErrRetentionHold
		}
		calculatedPart, err := client.Hash(client.ManifestPartHash, map[string]any{"run_id": runID, "part_no": business["part_no"], "members": members})
		if err != nil || calculatedPart != partHash {
			rows.Close()
			return "", 0, ErrRetentionHold
		}
		calculatedBusiness, err := client.Hash(client.ControlBusinessHash, map[string]any{"operation": "manifest", "business": business})
		if err != nil || calculatedBusiness != hash {
			rows.Close()
			return "", 0, ErrRetentionHold
		}
		parts = append(parts, part{Number: number, Hash: partHash, Count: len(members)})
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return "", 0, err
	}
	rows.Close()
	sort.Slice(parts, func(i, j int) bool { return parts[i].Number < parts[j].Number })
	rootParts := make([]any, 0, len(parts))
	memberCount := uint64(0)
	for i, p := range parts {
		if p.Number != uint64(i) {
			return "", 0, ErrRetentionHold
		}
		rootParts = append(rootParts, map[string]any{"part_no": p.Number, "part_hash": p.Hash, "member_count": p.Count})
		memberCount += uint64(p.Count)
	}
	if memberCount != expectedCount {
		return "", 0, ErrRetentionHold
	}
	root, err := client.Hash(client.ManifestHash, map[string]any{"run_id": runID, "expected_count": expectedCount, "parts": rootParts})
	return root, uint64(len(parts)), err
}

func validAcceptedTime(value *string) bool {
	if value == nil {
		return false
	}
	_, err := time.Parse("2006-01-02T15:04:05.000000Z", *value)
	return err == nil
}
