package outbox

import (
	"bufio"
	"bytes"
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"modernc.org/sqlite"
)

// This test driver delegates every operation to real SQLite. Only the child
// process pauses at a statement boundary; its parent then kills the process.
// No production crash switch or simulated database is involved.
type crashSQLiteDriver struct {
	mode  string
	armed bool
}
type crashSQLiteConn struct {
	driver.Conn
	owner *crashSQLiteDriver
}

func (d *crashSQLiteDriver) Open(name string) (driver.Conn, error) {
	c, err := (&sqlite.Driver{}).Open(name)
	if err != nil {
		return nil, err
	}
	return &crashSQLiteConn{Conn: c, owner: d}, nil
}
func (c *crashSQLiteConn) QueryContext(ctx context.Context, q string, args []driver.NamedValue) (driver.Rows, error) {
	return c.Conn.(driver.QueryerContext).QueryContext(ctx, q, args)
}
func (c *crashSQLiteConn) ExecContext(ctx context.Context, q string, args []driver.NamedValue) (driver.Result, error) {
	m := c.owner.mode
	insert := strings.HasPrefix(q, "INSERT INTO outbox")
	checkpoint := strings.HasPrefix(q, "UPDATE source_state SET payload=?")
	ack := strings.HasPrefix(q, "UPDATE outbox SET state=?,")
	remove := strings.HasPrefix(q, "DELETE FROM outbox")
	if c.owner.armed && ((m == "before_outbox" && insert) || (m == "before_ack" && ack) || (m == "before_delete" && remove) || (m == "before_commit" && q == "COMMIT")) {
		crashBarrier()
	}
	r, err := c.Conn.(driver.ExecerContext).ExecContext(ctx, q, args)
	if err == nil && c.owner.armed && ((m == "after_outbox" && insert) || (m == "after_checkpoint" && checkpoint) || (m == "after_ack" && ack) || (m == "after_delete" && remove) || (m == "after_commit" && q == "COMMIT")) {
		crashBarrier()
	}
	return r, err
}
func crashBarrier() {
	fmt.Println("FICTIONAL_SQLITE_BARRIER")
	for {
		time.Sleep(time.Hour)
	}
}

func frozenFixture(id string) client.FrozenObservation {
	r := fixtureRecord(id)
	return client.FrozenObservation{RecordID: r.ID, RunID: r.RunID, MemberID: r.MemberID, ObservationID: r.ObservationID, Wire: r.Payload, PayloadHash: r.PayloadHash}
}
func fixtureAcks(batch []client.FrozenObservation, status string) []client.ObservationAck {
	result := make([]client.ObservationAck, 0, len(batch))
	for _, r := range batch {
		ref, at := "fictional-receipt-"+r.RecordID, "2026-09-30T08:00:00.000000Z"
		result = append(result, client.ObservationAck{ObservationID: r.ObservationID, MemberID: r.MemberID, Status: status, ReceiptRef: &ref, AcceptedAt: &at})
	}
	return result
}

func TestSQLiteCrashHelper(t *testing.T) {
	mode := os.Getenv("FICTIONAL_SQLITE_CRASH")
	if mode == "" {
		t.Skip("parent-only crash helper")
	}
	path := os.Getenv("FICTIONAL_SQLITE_PATH")
	if !filepath.IsAbs(path) || filepath.Base(path) != "queue.db" {
		t.Fatal("fictional queue path required")
	}
	d := &crashSQLiteDriver{mode: mode}
	sql.Register("fictional-crash-sqlite", d)
	db, err := sql.Open("fictional-crash-sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	now := fixtureClock()
	if os.Getenv("FICTIONAL_SQLITE_OPERATION") == "cleanup" {
		now = now.Add(7 * 24 * time.Hour)
	}
	s := &Store{db: db, nodeID: "node", now: func() time.Time { return now }}
	if err = s.init(context.Background()); err != nil {
		t.Fatal(err)
	}
	d.armed = true
	switch os.Getenv("FICTIONAL_SQLITE_OPERATION") {
	case "page":
		err = s.SavePage(context.Background(), "run-1", "token-a", 0, "", "page-2", []Record{fixtureRecord("1"), fixtureRecord("2")})
	case "ack":
		batch := []client.FrozenObservation{frozenFixture("1"), frozenFixture("2")}
		err = s.ApplyObservationAcks(context.Background(), batch, fixtureAcks(batch, "accepted"))
	case "confirm":
		var proof TerminalSnapshot
		if err = json.Unmarshal([]byte(os.Getenv("FICTIONAL_SQLITE_PROOF")), &proof); err != nil {
			t.Fatal(err)
		}
		err = s.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: proof})
	case "cleanup":
		_, err = s.CleanupConfirmedObservations(context.Background(), "run-1", 1)
	default:
		t.Fatal("unknown fictional operation")
	}
	t.Fatalf("operation returned without reaching crash barrier: %v", err)
}

func killAtSQLiteBoundary(t *testing.T, path, op, mode string, proof *TerminalSnapshot) {
	t.Helper()
	cmd := exec.Command(os.Args[0], "-test.run=^TestSQLiteCrashHelper$", "-test.timeout=30s")
	cmd.Env = append(os.Environ(), "FICTIONAL_SQLITE_PATH="+path, "FICTIONAL_SQLITE_OPERATION="+op, "FICTIONAL_SQLITE_CRASH="+mode)
	if proof != nil {
		raw, err := json.Marshal(proof)
		if err != nil {
			t.Fatal(err)
		}
		cmd.Env = append(cmd.Env, "FICTIONAL_SQLITE_PROOF="+string(raw))
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }()
	reached := make(chan bool, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			if scanner.Text() == "FICTIONAL_SQLITE_BARRIER" {
				reached <- true
				return
			}
		}
		reached <- false
	}()
	select {
	case ok := <-reached:
		if !ok {
			t.Fatal("child exited before requested boundary")
		}
	case <-time.After(10 * time.Second):
		t.Fatal("SQLite boundary timed out")
	}
	if err = cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err = cmd.Wait(); err == nil {
		t.Fatal("child terminated normally instead of being killed")
	}
	if cmd.ProcessState == nil || cmd.ProcessState.Success() {
		t.Fatal("child exit not confirmed")
	}
	if stderr.Len() != 0 {
		t.Fatalf("unexpected child diagnostics: %s", stderr.String())
	}
}
func openFixtureQueue(t *testing.T, path string, now func() time.Time) *Store {
	t.Helper()
	s, err := Open(path, "node", now)
	if err != nil {
		t.Fatal(err)
	}
	return s
}
func queueFilename(t *testing.T, s *Store) string {
	t.Helper()
	var seq int
	var name, path string
	if err := s.db.QueryRow("PRAGMA database_list").Scan(&seq, &name, &path); err != nil {
		t.Fatal(err)
	}
	return path
}
func assertOriginalRows(t *testing.T, s *Store, state string) {
	t.Helper()
	for _, id := range []string{"1", "2"} {
		want := fixtureRecord(id)
		got, actual, err := s.Record(context.Background(), id)
		if err != nil || actual != state || got.RecordKey != want.RecordKey || got.ObservationID != want.ObservationID || got.MemberID != want.MemberID || got.PayloadHash != want.PayloadHash || !bytes.Equal(got.Payload, want.Payload) {
			t.Fatalf("frozen identity/bytes/state changed: %s %s %v", id, actual, err)
		}
	}
}

func TestSQLitePageActualProcessKillKeepsAtomicCursorAndTwoRows(t *testing.T) {
	for _, mode := range []string{"before_outbox", "after_outbox", "after_checkpoint", "after_commit"} {
		t.Run(mode, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "queue.db")
			s := openFixtureQueue(t, path, fixtureClock)
			if err := s.PersistClaim(fixtureClaim()); err != nil {
				t.Fatal(err)
			}
			if err := s.Close(); err != nil {
				t.Fatal(err)
			}
			killAtSQLiteBoundary(t, path, "page", mode, nil)
			s = openFixtureQueue(t, path, fixtureClock)
			defer s.Close()
			r, rev, err := s.Run(context.Background(), "run-1")
			count, e := s.ObservationCount(context.Background(), "run-1")
			if err != nil || e != nil {
				t.Fatal(err, e)
			}
			if mode == "after_commit" {
				if r.Cursor != "page-2" || rev != 1 || count != 2 {
					t.Fatal("committed page lost", r.Cursor, rev, count)
				}
				assertOriginalRows(t, s, "pending")
			} else if r.Cursor != "" || rev != 0 || count != 0 {
				t.Fatal("uncommitted page survived partially", r.Cursor, rev, count)
			}
		})
	}
}

func TestSQLiteAckActualProcessKillReplaysOriginalFrozenBatch(t *testing.T) {
	for _, mode := range []string{"before_ack", "after_ack", "after_commit"} {
		t.Run(mode, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "queue.db")
			s := openFixtureQueue(t, path, fixtureClock)
			if err := s.PersistClaim(fixtureClaim()); err != nil {
				t.Fatal(err)
			}
			if err := s.SavePage(context.Background(), "run-1", "token-a", 0, "", "page-2", []Record{fixtureRecord("1"), fixtureRecord("2")}); err != nil {
				t.Fatal(err)
			}
			if batch, err := s.NextObservationBatch(context.Background(), 10, 512<<10); err != nil || len(batch) != 2 {
				t.Fatal("missing sending batch", err)
			}
			if err := s.Close(); err != nil {
				t.Fatal(err)
			}
			killAtSQLiteBoundary(t, path, "ack", mode, nil)
			s = openFixtureQueue(t, path, fixtureClock)
			defer s.Close()
			if mode == "after_commit" {
				assertOriginalRows(t, s, "acknowledged")
				return
			}
			assertOriginalRows(t, s, "sending")
			if err := s.Recover(context.Background()); err != nil {
				t.Fatal(err)
			}
			assertOriginalRows(t, s, "pending")
			batch, err := s.NextObservationBatch(context.Background(), 10, 512<<10)
			if err != nil || len(batch) != 2 {
				t.Fatal("restart did not retransmit", err)
			}
			if err = s.ApplyObservationAcks(context.Background(), batch, fixtureAcks(batch, "duplicate")); err != nil {
				t.Fatal(err)
			}
			assertOriginalRows(t, s, "acknowledged")
		})
	}
}

func TestSQLiteTerminalMarkerAndCleanupActualProcessKill(t *testing.T) {
	for _, op := range []string{"confirm", "cleanup"} {
		modes := []string{"after_checkpoint", "after_commit"}
		if op == "cleanup" {
			modes = []string{"before_delete", "after_delete", "before_commit", "after_commit"}
		}
		for _, mode := range modes {
			t.Run(op+"/"+mode, func(t *testing.T) {
				s, clock, proof := terminalFixture(t)
				path := queueFilename(t, s)
				var controlsBefore string
				if err := s.db.QueryRow("SELECT group_concat(hex(payload)||':'||payload_hash||':'||state||':'||coalesce(receipt_ref,''),'|') FROM (SELECT * FROM outbox WHERE record_kind!='observation' ORDER BY id)").Scan(&controlsBefore); err != nil {
					t.Fatal(err)
				}
				if op == "cleanup" {
					if err := s.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: proof}); err != nil {
						t.Fatal(err)
					}
					*clock = clock.Add(7 * 24 * time.Hour)
				}
				if err := s.Close(); err != nil {
					t.Fatal(err)
				}
				killAtSQLiteBoundary(t, path, op, mode, &proof)
				s = openFixtureQueue(t, path, func() time.Time { return *clock })
				defer s.Close()
				r, _, err := s.Run(context.Background(), "run-1")
				if err != nil {
					t.Fatal(err)
				}
				count, err := s.ObservationCount(context.Background(), "run-1")
				if err != nil {
					t.Fatal(err)
				}
				var controlsAfter string
				if err = s.db.QueryRow("SELECT group_concat(hex(payload)||':'||payload_hash||':'||state||':'||coalesce(receipt_ref,''),'|') FROM (SELECT * FROM outbox WHERE record_kind!='observation' ORDER BY id)").Scan(&controlsAfter); err != nil || controlsAfter != controlsBefore {
					t.Fatal("control evidence changed", err)
				}
				if op == "confirm" {
					if count != 1 || (r.Retention != nil) != (mode == "after_commit") {
						t.Fatal("terminal marker committed partially")
					}
					if n, e := s.CleanupConfirmedObservations(context.Background(), "run-1", 1); n != 0 || !errors.Is(e, ErrRetentionHold) {
						t.Fatal("early cleanup", n, e)
					}
					return
				}
				if r.Retention == nil {
					t.Fatal("durable terminal marker lost")
				}
				if mode == "after_commit" {
					if count != 0 || r.Retention.CleanupState != "done" {
						t.Fatal("committed cleanup lost")
					}
				} else {
					if count != 1 || r.Retention.CleanupState == "done" {
						t.Fatal("uncommitted cleanup leaked")
					}
					if n, e := s.CleanupConfirmedObservations(context.Background(), "run-1", 1); n != 1 || e != nil {
						t.Fatal("cleanup could not resume", n, e)
					}
				}
				if err := s.SavePage(context.Background(), "run-1", "token-a", 0, "", "again", []Record{fixtureRecord("2")}); !errors.Is(err, ErrStaleRun) {
					t.Fatal("cleaned run could be refetched", err)
				}
				if batch, e := s.NextObservationBatch(context.Background(), 10, 512<<10); e != nil || len(batch) != 0 {
					t.Fatal("cleaned bytes retransmitted", e)
				}
			})
		}
	}
}

func TestSQLiteConsistentBackupReplaysWithoutChangingIdentity(t *testing.T) {
	path := filepath.Join(t.TempDir(), "queue.db")
	s := openFixtureQueue(t, path, fixtureClock)
	defer s.Close()
	if err := s.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	if err := s.SavePage(context.Background(), "run-1", "token-a", 0, "", "page-2", []Record{fixtureRecord("1"), fixtureRecord("2")}); err != nil {
		t.Fatal(err)
	}
	batch, err := s.NextObservationBatch(context.Background(), 10, 512<<10)
	if err != nil {
		t.Fatal(err)
	}
	backup := filepath.Join(t.TempDir(), "queue.db")
	if _, err = s.db.Exec("VACUUM INTO ?", backup); err != nil {
		t.Fatal("consistent backup failed", err)
	}
	if err = s.ApplyObservationAcks(context.Background(), batch, fixtureAcks(batch, "accepted")); err != nil {
		t.Fatal(err)
	}
	assertOriginalRows(t, s, "acknowledged")
	restored := openFixtureQueue(t, backup, fixtureClock)
	defer restored.Close()
	assertOriginalRows(t, restored, "sending")
	if err = restored.Recover(context.Background()); err != nil {
		t.Fatal(err)
	}
	replay, err := restored.NextObservationBatch(context.Background(), 10, 512<<10)
	if err != nil || len(replay) != 2 {
		t.Fatal("backup replay lost", err)
	}
	if err = restored.ApplyObservationAcks(context.Background(), replay, fixtureAcks(replay, "duplicate")); err != nil {
		t.Fatal(err)
	}
	assertOriginalRows(t, restored, "acknowledged")
}

func TestSQLitePageLimitFailureAndClockRollbackPreserveEvidence(t *testing.T) {
	path := filepath.Join(t.TempDir(), "queue.db")
	s := openFixtureQueue(t, path, fixtureClock)
	defer s.Close()
	if err := s.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	var pages int
	if err := s.db.QueryRow("PRAGMA page_count").Scan(&pages); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec(fmt.Sprintf("PRAGMA max_page_count=%d", pages)); err != nil {
		t.Fatal(err)
	}
	r := fixtureRecord("1")
	r.Payload = bytes.Repeat([]byte("x"), 1<<20)
	var full *sqlite.Error
	if err := s.SavePage(context.Background(), "run-1", "token-a", 0, "", "page-2", []Record{r}); !errors.As(err, &full) || full.Code() != 13 {
		t.Fatal("actual SQLite capacity failure missing", err)
	}
	run, rev, err := s.Run(context.Background(), "run-1")
	n, e := s.ObservationCount(context.Background(), "run-1")
	if err != nil || e != nil || rev != 0 || run.Cursor != "" || n != 0 {
		t.Fatal("full database left partial page", err, e, rev, n)
	}
	s2, clock, proof := terminalFixture(t)
	defer s2.Close()
	if err := s2.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: proof}); err != nil {
		t.Fatal(err)
	}
	*clock = clock.Add(-time.Microsecond)
	if n, err := s2.CleanupConfirmedObservations(context.Background(), "run-1", 1); n != 0 || !errors.Is(err, ErrClockRollback) {
		t.Fatal("clock rollback allowed cleanup", n, err)
	}
	got, _, err := s2.Record(context.Background(), "1")
	if err != nil || !bytes.Equal(got.Payload, fixtureRecord("1").Payload) {
		t.Fatal("rollback lost payload", err)
	}
}

func TestSQLiteNineAcksAndOneQuarantineSurviveDayEightRestart(t *testing.T) {
	s, clock, oldProof := terminalFixture(t)
	path := queueFilename(t, s)
	records := []Record{}
	for i := 2; i <= 10; i++ {
		records = append(records, fixtureRecord(fmt.Sprintf("%x", i)))
	}
	if err := s.SavePage(context.Background(), "run-1", "token-a", 1, "end", "end-2", records); err != nil {
		t.Fatal(err)
	}
	batch, err := s.NextObservationBatch(context.Background(), 10, 512<<10)
	if err != nil || len(batch) != 9 {
		t.Fatal("missing fictional page", err)
	}
	if err = s.ApplyObservationAcks(context.Background(), batch[:8], fixtureAcks(batch[:8], "accepted")); err != nil {
		t.Fatal(err)
	}
	if err = s.QuarantineObservations(context.Background(), batch[8:]); err != nil {
		t.Fatal(err)
	}
	var acked, quarantined int
	if err = s.db.QueryRow("SELECT count(*) FROM outbox WHERE record_kind='observation' AND state='acknowledged'").Scan(&acked); err != nil {
		t.Fatal(err)
	}
	if err = s.db.QueryRow("SELECT count(*) FROM outbox WHERE record_kind='observation' AND state='quarantine'").Scan(&quarantined); err != nil || acked != 9 || quarantined != 1 {
		t.Fatal("fixture must contain nine ACKs and one quarantine", acked, quarantined, err)
	}
	*clock = clock.Add(8 * 24 * time.Hour)
	if err = s.ConfirmTerminal(context.Background(), "run-1", terminalReader{snapshot: oldProof}); err == nil {
		t.Fatal("incomplete manifest confirmed terminal")
	}
	if n, e := s.CleanupConfirmedObservations(context.Background(), "run-1", 10); n != 0 || !errors.Is(e, ErrRetentionHold) {
		t.Fatal("unterminated run cleaned", n, e)
	}
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	s = openFixtureQueue(t, path, func() time.Time { return *clock })
	defer s.Close()
	count, err := s.ObservationCount(context.Background(), "run-1")
	if err != nil || count != 10 {
		t.Fatal("day-eight restart lost observations", count, err)
	}
	for i := 1; i <= 10; i++ {
		want := fixtureRecord(fmt.Sprintf("%x", i))
		got, _, e := s.Record(context.Background(), want.ID)
		if e != nil || got.ObservationID != want.ObservationID || got.RecordKey != want.RecordKey || got.PayloadHash != want.PayloadHash || !bytes.Equal(got.Payload, want.Payload) {
			t.Fatal("unterminated frozen observation changed", i, e)
		}
	}
	if n, e := s.CleanupConfirmedObservations(context.Background(), "run-1", 10); n != 0 || !errors.Is(e, ErrRetentionHold) {
		t.Fatal("restart bypassed terminal proof", n, e)
	}
}
