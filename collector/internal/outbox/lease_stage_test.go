package outbox

import (
	"bytes"
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
)

func leaseReceipt(a client.ControlAttempt, until string) client.ControlResult {
	run := a.RunID
	return client.ControlResult{BusinessHash: a.BusinessHash, RunID: &run, ReceiptRef: "fictional-lease-receipt-" + a.ID, CurrentLeaseValid: true, LeaseUntil: &until}
}
func confirmFixtureLease(t *testing.T, s *Store, until string) {
	t.Helper()
	a, err := s.NextControlAttempt(context.Background())
	if err != nil || a == nil {
		t.Fatal("missing lease attempt", err)
	}
	if ok, err := s.ApplyControlReceipt(context.Background(), *a, leaseReceipt(*a, until)); err != nil || !ok {
		t.Fatal("lease not confirmed", err)
	}
}
func leaseMicros(t *testing.T, s *Store) int64 {
	t.Helper()
	var until int64
	if err := s.db.QueryRow("SELECT lease_until FROM source_state WHERE state_key='run-1' AND record_kind='run'").Scan(&until); err != nil {
		t.Fatal(err)
	}
	return until
}

func TestLeaseActionsPersistEachRenewAndReplayOnlyUnresolvedIdentity(t *testing.T) {
	ctx := context.Background()
	now := fixtureClock()
	path := filepath.Join(t.TempDir(), "queue.db")
	s := openFixtureQueue(t, path, func() time.Time { return now })
	defer func() { s.Close() }()
	if err := s.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	if _, err := s.StageLeaseAction(ctx, "run-1", "token-a", "renew"); !errors.Is(err, ErrStaleRun) {
		t.Fatal("renew before ack allowed", err)
	}
	confirmFixtureLease(t, s, "2026-09-30T09:00:00.000000Z")
	if err := s.SavePage(ctx, "run-1", "token-a", 0, "", "page-2", []Record{fixtureRecord("1")}); err != nil {
		t.Fatal(err)
	}
	a, err := s.StageLeaseAction(ctx, "run-1", "token-a", "renew")
	if err != nil {
		t.Fatal(err)
	}
	selected, err := s.NextControlAttempt(ctx)
	if err != nil || selected == nil || selected.ID != a.ID {
		t.Fatal("wrong A", err)
	}
	if ok, e := s.ApplyControlReceipt(ctx, *selected, leaseReceipt(*selected, "2026-09-30T09:30:00.000000Z")); !ok || e != nil {
		t.Fatal(e)
	}
	now = now.Add(20 * time.Second)
	b, err := s.StageLeaseAction(ctx, "run-1", "token-a", "renew")
	if err != nil || b.BusinessID == a.BusinessID || b.ID == a.ID || b.IdempotencyKey == a.IdempotencyKey || b.BusinessHash != a.BusinessHash {
		t.Fatal("renew periods collapsed", err)
	}
	var aKey, bKey string
	s.db.QueryRow("SELECT record_key FROM outbox WHERE id=?", a.BusinessID).Scan(&aKey)
	s.db.QueryRow("SELECT record_key FROM outbox WHERE id=?", b.BusinessID).Scan(&bKey)
	if aKey == bKey {
		t.Fatal("renew record keys collapsed")
	}
	selected, err = s.NextControlAttempt(ctx)
	if err != nil || selected == nil || selected.ID != b.ID {
		t.Fatal("wrong B", err)
	}
	// B was sent and its HTTP ACK was lost. A later timer cannot create C.
	replay, err := s.StageLeaseAction(ctx, "run-1", "token-a", "renew")
	if err != nil || replay.ID != b.ID || replay.BusinessID != b.BusinessID || replay.IdempotencyKey != b.IdempotencyKey || !bytes.Equal(replay.Wire, b.Wire) {
		t.Fatal("unresolved B replaced", err)
	}
	if _, err = s.StageLeaseAction(ctx, "run-1", "token-a", "release"); !errors.Is(err, ErrConflict) {
		t.Fatal("parallel release allowed", err)
	}
	before := leaseMicros(t, s)
	if ok, err := s.ApplyControlReceipt(ctx, a, leaseReceipt(a, "2026-09-30T15:00:00.000000Z")); err != nil || ok || leaseMicros(t, s) != before {
		t.Fatal("late A changed B lease", err)
	}
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	s = openFixtureQueue(t, path, func() time.Time { return now })
	if err = s.Recover(ctx); err != nil {
		t.Fatal(err)
	}
	picked, err := s.NextControlAttempt(ctx)
	if err != nil || picked == nil || picked.ID != b.ID || !bytes.Equal(picked.Wire, b.Wire) {
		t.Fatal("restart did not replay B", err)
	}
	until := "2026-09-30T10:00:00.000000Z"
	if ok, err := s.ApplyControlReceipt(ctx, *picked, leaseReceipt(*picked, until)); err != nil || !ok {
		t.Fatal(err)
	}
	expected, _ := time.Parse("2006-01-02T15:04:05.000000Z", until)
	if leaseMicros(t, s) != micros(expected) {
		t.Fatal("confirmed B lease not persisted")
	}
	before = leaseMicros(t, s)
	if ok, err := s.ApplyControlReceipt(ctx, *picked, leaseReceipt(*picked, "2026-09-30T16:00:00.000000Z")); err != nil || ok || leaseMicros(t, s) != before {
		t.Fatal("B local duplicate extended lease", err)
	}
	var actions, attempts int
	s.db.QueryRow("SELECT count(*) FROM outbox WHERE record_kind='lease_action' AND json_extract(payload,'$.action')='renew'").Scan(&actions)
	s.db.QueryRow("SELECT count(*) FROM outbox a JOIN outbox b ON b.id=a.business_ref WHERE json_extract(b.payload,'$.action')='renew'").Scan(&attempts)
	if actions != 2 || attempts != 2 {
		t.Fatal("expected exactly two renew actions and attempts", actions, attempts)
	}
	got, _, err := s.Record(ctx, "1")
	if err != nil || !bytes.Equal(got.Payload, fixtureRecord("1").Payload) {
		t.Fatal("lease mutated observation", err)
	}
}

func TestLeaseOldRenewOrReleaseCannotTouchNewClaimAndAckHasNoCycle(t *testing.T) {
	for _, kind := range []string{"renew", "release"} {
		t.Run(kind, func(t *testing.T) {
			ctx := context.Background()
			now := fixtureClock()
			s := openFixtureQueue(t, filepath.Join(t.TempDir(), "queue.db"), func() time.Time { return now })
			defer s.Close()
			if err := s.PersistClaim(fixtureClaim()); err != nil {
				t.Fatal(err)
			}
			confirmFixtureLease(t, s, "2026-09-30T09:00:00.000000Z")
			old, err := s.StageLeaseAction(ctx, "run-1", "token-a", kind)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = s.NextControlAttempt(ctx); err != nil {
				t.Fatal(err)
			}
			now = now.Add(61 * time.Minute)
			if _, err = s.StageLeaseAction(ctx, "run-1", "token-a", "renew"); !errors.Is(err, ErrStaleRun) {
				t.Fatal("expired lease staged", err)
			}
			claim := fixtureClaim()
			token, until := "token-b", "2026-09-30T10:00:00.000000Z"
			claim.ClaimToken = &token
			claim.LeaseUntil = &until
			if err = s.PersistClaim(claim); err != nil {
				t.Fatal(err)
			}
			before := leaseMicros(t, s)
			if ok, e := s.ApplyControlReceipt(ctx, old, leaseReceipt(old, "2026-09-30T18:00:00.000000Z")); ok || e != nil || leaseMicros(t, s) != before {
				t.Fatal("old lease action changed new lease", e)
			}
			for _, id := range []string{old.ID, old.BusinessID} {
				_, state, e := s.Record(ctx, id)
				if e != nil || state != "superseded" {
					t.Fatal("old action evidence lost", id, state, e)
				}
			}
			confirmFixtureLease(t, s, until)
			if _, err = s.StageLeaseAction(ctx, "run-1", "token-b", "renew"); err != nil {
				t.Fatal("new ACK cannot unlock new lease", err)
			}
		})
	}
}

func TestLeaseReceiptValidationAndReleaseNeverGrantTime(t *testing.T) {
	ctx := context.Background()
	now := fixtureClock()
	s := openFixtureQueue(t, filepath.Join(t.TempDir(), "queue.db"), func() time.Time { return now })
	defer s.Close()
	if err := s.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	confirmFixtureLease(t, s, "2026-09-30T09:00:00.000000Z")
	a, err := s.StageLeaseAction(ctx, "run-1", "token-a", "renew")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.NextControlAttempt(ctx); err != nil {
		t.Fatal(err)
	}
	before := leaseMicros(t, s)
	for _, mutate := range []func(*client.ControlResult){func(r *client.ControlResult) { r.ReceiptRef = "" }, func(r *client.ControlResult) { run := "other-run"; r.RunID = &run }, func(r *client.ControlResult) { r.BusinessHash = "bad" }, func(r *client.ControlResult) { r.LeaseUntil = nil }, func(r *client.ControlResult) { bad := "2026-09-30T10:00:00+00:00"; r.LeaseUntil = &bad }} {
		r := leaseReceipt(a, "2026-09-30T10:00:00.000000Z")
		mutate(&r)
		if ok, e := s.ApplyControlReceipt(ctx, a, r); ok || !errors.Is(e, ErrConflict) || leaseMicros(t, s) != before {
			t.Fatal("invalid lease receipt changed state", e)
		}
	}
	// A server read says the original operation committed but the lease is no longer valid.
	r := leaseReceipt(a, "2026-09-30T12:00:00.000000Z")
	r.CurrentLeaseValid = false
	if ok, e := s.ApplyControlReceipt(ctx, a, r); !ok || e != nil || leaseMicros(t, s) != micros(now) {
		t.Fatal("historical receipt granted time", e)
	}
	if _, err = s.StageLeaseAction(ctx, "run-1", "token-a", "renew"); !errors.Is(err, ErrStaleRun) {
		t.Fatal("invalidated lease staged", err)
	}
	claim := fixtureClaim()
	token := "token-c"
	claim.ClaimToken = &token
	if err = s.PersistClaim(claim); err != nil {
		t.Fatal(err)
	}
	confirmFixtureLease(t, s, "2026-09-30T09:00:00.000000Z")
	before = leaseMicros(t, s)
	a, err = s.StageLeaseAction(ctx, "run-1", "token-c", "release")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.NextControlAttempt(ctx); err != nil {
		t.Fatal(err)
	}
	r = leaseReceipt(a, "2026-09-30T12:00:00.000000Z")
	if ok, e := s.ApplyControlReceipt(ctx, a, r); ok || !errors.Is(e, ErrConflict) || leaseMicros(t, s) != before {
		t.Fatal("release granted lease", e)
	}
	r.CurrentLeaseValid = false
	r.LeaseUntil = nil
	if ok, e := s.ApplyControlReceipt(ctx, a, r); !ok || e != nil || leaseMicros(t, s) != micros(now) {
		t.Fatal("release did not expire local lease", e)
	}
	if _, err = s.StageLeaseAction(ctx, "run-1", "token-c", "renew"); !errors.Is(err, ErrStaleRun) {
		t.Fatal("released lease renewed locally", err)
	}
}
