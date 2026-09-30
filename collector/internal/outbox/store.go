package outbox

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strings"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	_ "modernc.org/sqlite"
)

const schemaVersion = "3"
const maxSafe = 9007199254740991

var ErrLegacyQueue = errors.New("old collector queue requires identity audit")
var ErrConflict = errors.New("immutable queue identity conflict")
var ErrStaleRun = errors.New("run checkpoint or claim changed")

type Store struct {
	db     *sql.DB
	nodeID string
	now    func() time.Time
}

type Record struct {
	ID                    string
	Kind                  string
	RecordKey             string
	SourceID              string
	RunID                 string
	MemberID              string
	ObservationID         string
	Payload               []byte
	PayloadHash           string
	ProtocolRevision      string
	HashVersion           int
	ReplacesObservationID string
	BusinessRef           string
	SupersedesAttemptID   string
	IdempotencyKey        string
}

type RunState struct {
	Frozen    client.RunClaim `json:"frozen"`
	Cursor    string          `json:"cursor"`
	Retention *RetentionMark  `json:"retention,omitempty"`
}

func Open(path, nodeID string, now func() time.Time) (*Store, error) {
	if path == "" || nodeID == "" || now == nil {
		return nil, errors.New("queue path, node identity and clock required")
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	store := &Store{db: db, nodeID: nodeID, now: now}
	if err := store.init(context.Background()); err != nil {
		db.Close()
		return nil, err
	}
	return store, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) init(ctx context.Context) error {
	for _, pragma := range []string{"PRAGMA foreign_keys=ON", "PRAGMA journal_mode=WAL", "PRAGMA synchronous=FULL", "PRAGMA busy_timeout=5000"} {
		if _, err := s.db.ExecContext(ctx, pragma); err != nil {
			return err
		}
	}
	if _, err := s.db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS node_config (
key TEXT PRIMARY KEY NOT NULL,value BLOB NOT NULL,revision INTEGER NOT NULL DEFAULT 0,
updated_at INTEGER NOT NULL)`); err != nil {
		return err
	}
	var version []byte
	err := s.db.QueryRowContext(ctx, "SELECT value FROM node_config WHERE key='schema_version'").Scan(&version)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if err == nil && string(version) != schemaVersion {
		return ErrLegacyQueue
	}
	return s.write(ctx, func(conn *sql.Conn) error {
		if _, err := conn.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS source_state (
record_kind TEXT NOT NULL,state_key TEXT NOT NULL,source_id TEXT NOT NULL,run_id TEXT NULL,
revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0 AND revision<=9007199254740991),
payload BLOB NOT NULL,claim_token BLOB NULL,lease_until INTEGER NULL,next_fetch_at INTEGER NULL,
last_success_at INTEGER NULL,failure_count INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,
updated_at INTEGER NOT NULL,PRIMARY KEY(record_kind,state_key),UNIQUE(run_id),
CHECK((record_kind='source' AND run_id IS NULL AND state_key=source_id) OR
(record_kind='run' AND run_id IS NOT NULL AND state_key=run_id)))`); err != nil {
			return err
		}
		if _, err := conn.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS outbox (
id TEXT PRIMARY KEY NOT NULL,record_kind TEXT NOT NULL,record_key TEXT NOT NULL,
source_id TEXT NOT NULL,run_id TEXT NULL,member_id TEXT NULL,observation_id TEXT NULL,
payload BLOB NOT NULL,payload_hash TEXT NOT NULL,protocol_revision TEXT NOT NULL,
hash_version INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'pending',
revision INTEGER NOT NULL DEFAULT 0,send_attempts INTEGER NOT NULL DEFAULT 0,
next_retry_at INTEGER NOT NULL,created_at INTEGER NOT NULL,acknowledged_at INTEGER NULL,
receipt_ref TEXT NULL,ack_blob BLOB NULL,last_error_code TEXT NULL,
replaces_observation_id TEXT NULL,business_ref TEXT NULL REFERENCES outbox(id) ON DELETE RESTRICT,
supersedes_attempt_id TEXT NULL REFERENCES outbox(id) ON DELETE RESTRICT,
idempotency_key TEXT NULL,UNIQUE(record_kind,record_key),UNIQUE(observation_id),UNIQUE(idempotency_key),
CHECK(state IN ('pending','sending','acknowledged','quarantine','sealed','superseded')),
CHECK(revision>=0 AND revision<=9007199254740991),
CHECK((record_kind='observation' AND run_id IS NOT NULL AND member_id IS NOT NULL AND observation_id IS NOT NULL)
OR (record_kind!='observation' AND observation_id IS NULL)),
CHECK((record_kind='control_attempt' AND business_ref IS NOT NULL AND idempotency_key IS NOT NULL)
OR (record_kind!='control_attempt' AND business_ref IS NULL AND idempotency_key IS NULL)))`); err != nil {
			return err
		}
		for _, stmt := range []string{
			"CREATE INDEX IF NOT EXISTS ix_source_state_source ON source_state(source_id,record_kind,status)",
			"CREATE INDEX IF NOT EXISTS ix_source_state_fetch ON source_state(next_fetch_at,status)",
			"CREATE INDEX IF NOT EXISTS ix_outbox_due ON outbox(state,next_retry_at,id)",
			"CREATE INDEX IF NOT EXISTS ix_outbox_member ON outbox(run_id,member_id)",
			"CREATE INDEX IF NOT EXISTS ix_outbox_ack ON outbox(state,acknowledged_at)",
			"CREATE INDEX IF NOT EXISTS ix_outbox_business ON outbox(business_ref,state,id)",
		} {
			if _, err := conn.ExecContext(ctx, stmt); err != nil {
				return err
			}
		}
		if errors.Is(err, sql.ErrNoRows) {
			_, err = conn.ExecContext(ctx, "INSERT INTO node_config(key,value,revision,updated_at) VALUES('schema_version',?,0,?)", []byte(schemaVersion), micros(s.now()))
			return err
		}
		return nil
	})
}

func (s *Store) PersistClaim(claim client.RunClaim) error {
	if claim.Run.RunID == "" || claim.Run.SourceID == "" || claim.ClaimToken == nil || claim.LeaseUntil == nil {
		return ErrStaleRun
	}
	lease, err := time.Parse("2006-01-02T15:04:05.000000Z", *claim.LeaseUntil)
	if err != nil || !s.now().Before(lease) {
		return ErrStaleRun
	}
	return s.write(context.Background(), func(conn *sql.Conn) error {
		var oldPayload, oldToken []byte
		var oldLease int64
		err := conn.QueryRowContext(context.Background(), "SELECT payload,claim_token,lease_until FROM source_state WHERE record_kind='run' AND state_key=?", claim.Run.RunID).Scan(&oldPayload, &oldToken, &oldLease)
		missing := errors.Is(err, sql.ErrNoRows)
		sameToken := !missing && string(oldToken) == *claim.ClaimToken
		var state RunState
		if err == nil {
			if json.Unmarshal(oldPayload, &state) != nil || !sameFrozen(state.Frozen, claim) {
				return ErrConflict
			}
			if state.Retention != nil {
				return ErrStaleRun
			}
			if sameToken && oldLease != micros(lease) {
				return ErrConflict
			}
			if string(oldToken) != *claim.ClaimToken && micros(s.now()) < oldLease {
				return ErrStaleRun
			}
		} else if !missing {
			return err
		}
		frozen := claim
		frozen.ClaimToken = nil
		frozen.LeaseUntil = nil
		frozen.CurrentLeaseValid = false
		state.Frozen = frozen
		payload, err := json.Marshal(state)
		if err != nil {
			return err
		}
		if missing {
			_, err = conn.ExecContext(context.Background(), `INSERT INTO source_state(record_kind,state_key,source_id,run_id,payload,claim_token,lease_until,status,updated_at)
VALUES('run',?,?,?,?,?,?,?,?)`, claim.Run.RunID, claim.Run.SourceID, claim.Run.RunID, payload, []byte(*claim.ClaimToken), micros(lease), "claimed", micros(s.now()))
		} else {
			_, err = conn.ExecContext(context.Background(), "UPDATE source_state SET payload=?,claim_token=?,lease_until=?,updated_at=? WHERE record_kind='run' AND state_key=?", payload, []byte(*claim.ClaimToken), micros(lease), micros(s.now()), claim.Run.RunID)
		}
		if err != nil {
			return err
		}
		if sameToken {
			return nil
		}
		if !missing {
			if _, err = conn.ExecContext(context.Background(), "UPDATE outbox SET state='superseded',revision=revision+1 WHERE run_id=? AND record_kind IN ('lease_action','control_attempt') AND state IN ('pending','sending')", claim.Run.RunID); err != nil {
				return err
			}
		}
		return s.stageAck(conn, claim)
	})
}

func (s *Store) stageAck(conn *sql.Conn, claim client.RunClaim) error {
	operationID, err := randomID()
	if err != nil {
		return err
	}
	action, err := client.PrepareLeaseAction("ack", claim, operationID, s.now())
	if err != nil {
		return err
	}
	business := map[string]any{"run_id": claim.Run.RunID, "action": "ack"}
	businessBytes, err := client.CanonicalBytes(business)
	if err != nil {
		return err
	}
	keyHash := domainHash("lease-operation-v1", map[string]any{"node_id": s.nodeID, "run_id": claim.Run.RunID, "action": "ack", "operation_id": operationID})
	now := micros(s.now())
	_, err = conn.ExecContext(context.Background(), `INSERT INTO outbox(id,record_kind,record_key,source_id,run_id,payload,payload_hash,protocol_revision,hash_version,next_retry_at,created_at)
VALUES(?,'lease_action',?,?,?,?,?,'0.11',1,?,?)`, operationID, keyHash, claim.Run.SourceID, claim.Run.RunID, businessBytes, action.BusinessHash, now, now)
	if err != nil {
		return err
	}
	attemptID, err := randomID()
	if err != nil {
		return err
	}
	attemptHash, err := client.Hash(client.ControlAttemptHash, map[string]any{"node_id": s.nodeID, "operation": "claim_ack", "business_hash": action.BusinessHash,
		"attempt": map[string]any{"claim_token": *claim.ClaimToken, "expected_run_revision": nil}})
	if err != nil {
		return err
	}
	_, err = conn.ExecContext(context.Background(), `INSERT INTO outbox(id,record_kind,record_key,source_id,run_id,payload,payload_hash,protocol_revision,hash_version,next_retry_at,created_at,business_ref,idempotency_key)
VALUES(?,'control_attempt',?,?,?,?,?,'0.11',1,?,?,?,?)`, attemptID, operationID, claim.Run.SourceID, claim.Run.RunID, action.Body, attemptHash, now, now, operationID, operationID)
	return err
}

func (s *Store) SavePage(ctx context.Context, runID, token string, expectedRevision uint64,
	before, after string, records []Record) error {
	if runID == "" || token == "" || expectedRevision > maxSafe || len(records) > 100 {
		return ErrStaleRun
	}
	return s.write(ctx, func(conn *sql.Conn) error {
		var payload, claimToken []byte
		var revision uint64
		var lease int64
		if err := conn.QueryRowContext(ctx, "SELECT payload,claim_token,lease_until,revision FROM source_state WHERE record_kind='run' AND state_key=?", runID).Scan(&payload, &claimToken, &lease, &revision); err != nil {
			return err
		}
		var state RunState
		if json.Unmarshal(payload, &state) != nil || state.Retention != nil || revision != expectedRevision || state.Cursor != before || string(claimToken) != token || micros(s.now()) >= lease {
			return ErrStaleRun
		}
		for _, record := range records {
			if record.Kind != "observation" || record.RunID != runID || record.SourceID != state.Frozen.Run.SourceID {
				return ErrConflict
			}
			if err := insertRecord(ctx, conn, record, micros(s.now())); err != nil {
				return err
			}
		}
		state.Cursor = after
		updated, err := json.Marshal(state)
		if err != nil {
			return err
		}
		result, err := conn.ExecContext(ctx, "UPDATE source_state SET payload=?,revision=revision+1,updated_at=? WHERE record_kind='run' AND state_key=? AND revision=?", updated, micros(s.now()), runID, expectedRevision)
		if err != nil {
			return err
		}
		count, err := result.RowsAffected()
		if err != nil || count != 1 {
			return ErrStaleRun
		}
		return nil
	})
}

func insertRecord(ctx context.Context, conn *sql.Conn, r Record, now int64) error {
	if r.ID == "" || !validDigest(r.RecordKey) || r.MemberID == "" || r.ObservationID == "" || len(r.Payload) == 0 || r.ProtocolRevision != "0.11" || r.HashVersion != 1 || !validDigest(r.PayloadHash) {
		return ErrConflict
	}
	_, err := conn.ExecContext(ctx, `INSERT INTO outbox(id,record_kind,record_key,source_id,run_id,member_id,observation_id,payload,payload_hash,protocol_revision,hash_version,next_retry_at,created_at,replaces_observation_id)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, r.ID, r.Kind, r.RecordKey, r.SourceID, r.RunID, r.MemberID, r.ObservationID, r.Payload, r.PayloadHash, r.ProtocolRevision, r.HashVersion, now, now, nullString(r.ReplacesObservationID))
	return err
}

func (s *Store) Recover(ctx context.Context) error {
	return s.write(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, "UPDATE outbox SET state='pending',revision=revision+1 WHERE state='sending'")
		return err
	})
}

func (s *Store) Run(ctx context.Context, id string) (RunState, uint64, error) {
	var raw []byte
	var revision uint64
	err := s.db.QueryRowContext(ctx, "SELECT payload,revision FROM source_state WHERE record_kind='run' AND state_key=?", id).Scan(&raw, &revision)
	var state RunState
	if err == nil {
		err = json.Unmarshal(raw, &state)
	}
	return state, revision, err
}

func (s *Store) ObservationCount(ctx context.Context, runID string) (int, error) {
	var count int
	err := s.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM outbox WHERE record_kind='observation' AND run_id=?", runID).Scan(&count)
	return count, err
}

func (s *Store) HasMember(ctx context.Context, runID, memberID string) (bool, error) {
	var exists int
	err := s.db.QueryRowContext(ctx, "SELECT 1 FROM outbox WHERE record_kind='observation' AND run_id=? AND member_id=? LIMIT 1", runID, memberID).Scan(&exists)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

func (s *Store) Record(ctx context.Context, id string) (Record, string, error) {
	var r Record
	var state string
	err := s.db.QueryRowContext(ctx, "SELECT id,record_kind,record_key,source_id,COALESCE(run_id,''),COALESCE(member_id,''),COALESCE(observation_id,''),payload,payload_hash,protocol_revision,hash_version,state FROM outbox WHERE id=?", id).
		Scan(&r.ID, &r.Kind, &r.RecordKey, &r.SourceID, &r.RunID, &r.MemberID, &r.ObservationID, &r.Payload, &r.PayloadHash, &r.ProtocolRevision, &r.HashVersion, &state)
	return r, state, err
}

func (s *Store) write(ctx context.Context, apply func(*sql.Conn) error) (err error) {
	conn, err := s.db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	if _, err = conn.ExecContext(ctx, "BEGIN IMMEDIATE"); err != nil {
		return err
	}
	defer func() {
		if err != nil {
			_, _ = conn.ExecContext(context.Background(), "ROLLBACK")
		}
	}()
	if err = apply(conn); err != nil {
		return err
	}
	_, err = conn.ExecContext(ctx, "COMMIT")
	return err
}

func randomID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
func micros(t time.Time) int64 { return t.UTC().UnixMicro() }
func nullString(v string) any {
	if v == "" {
		return nil
	}
	return v
}

func validDigest(value string) bool {
	if len(value) != 64 || strings.ToLower(value) != value {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func sameFrozen(a, b client.RunClaim) bool {
	return a.Run.RunID == b.Run.RunID && a.Run.SourceID == b.Run.SourceID &&
		a.Run.RunKind == b.Run.RunKind && a.Run.BusinessDate == b.Run.BusinessDate &&
		a.Run.Generation == b.Run.Generation && a.Run.CollectionReason == b.Run.CollectionReason &&
		a.Run.ConfigVersion == b.Run.ConfigVersion && a.Run.FetchMode == b.Run.FetchMode &&
		a.Run.WindowStart == b.Run.WindowStart && a.Run.WindowEnd == b.Run.WindowEnd &&
		reflect.DeepEqual(a.Config, b.Config) && sameJSON(a.TargetVersion, b.TargetVersion)
}

func sameJSON(a, b []byte) bool {
	if len(a) == 0 || bytes.Equal(a, []byte("null")) {
		return len(b) == 0 || bytes.Equal(b, []byte("null"))
	}
	if len(b) == 0 {
		return false
	}
	left, err := client.ParseStrict(a)
	if err != nil {
		return false
	}
	right, err := client.ParseStrict(b)
	if err != nil {
		return false
	}
	return reflect.DeepEqual(left, right)
}
func domainHash(domain string, value any) string {
	bytes, err := client.CanonicalBytes(value)
	if err != nil {
		panic(fmt.Sprintf("invalid internal hash: %v", err))
	}
	part := sha256.Sum256(append([]byte(domain+"\n"), bytes...))
	return hex.EncodeToString(part[:])
}
