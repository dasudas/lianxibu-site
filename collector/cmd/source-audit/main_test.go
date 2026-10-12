package main

import (
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestAuditWaitOnlyForShortKnownBudgetDelays(t *testing.T) {
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		delay time.Duration
		wait  bool
	}{{time.Second, true}, {3 * time.Second, true}, {5 * time.Second, true}, {6 * time.Second, false}, {time.Hour, false}, {0, true}, {-time.Millisecond, true}, {-time.Second, true}, {-6 * time.Second, false}} {
		duration, ok := shortBudgetWait(&fetcher.HostDeferredError{RetryAt: now.Add(tc.delay)}, now)
		expected := tc.delay
		if expected <= 0 {
			expected = time.Millisecond
		}
		if ok != tc.wait || (ok && duration != expected) {
			t.Fatalf("delay=%v wait=%v duration=%v", tc.delay, ok, duration)
		}
	}
	for _, err := range []error{fetcher.ErrHostDeferred, errors.New("fixture failure"), nil} {
		if _, ok := shortBudgetWait(err, now); ok {
			t.Fatal("unknown delay must not loop")
		}
	}
}

func TestAuditLoadsOnlyBoundedHostOwnedLicenseReviewFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "reviews.json")
	if err := os.WriteFile(path, []byte(`[{"repository":"fictional/project","commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","path":"LICENSE","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadAuditReviews(path); err != nil {
		t.Fatal(err)
	}
	if _, err := loadAuditReviews(""); err != nil {
		t.Fatal(err)
	}
	for _, invalid := range []string{"relative.json", filepath.Dir(path), path + ".missing"} {
		if _, err := loadAuditReviews(invalid); err == nil {
			t.Fatal("invalid host file accepted")
		}
	}
	for _, raw := range [][]byte{[]byte(`{"unknown":true}`), make([]byte, 65537)} {
		if err := os.WriteFile(path, raw, 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := loadAuditReviews(path); err == nil {
			t.Fatal("invalid review data accepted")
		}
	}
}

func TestAuditFailureClassificationDoesNotExposeWrappedNetworkDetail(t *testing.T) {
	if auditFailureCode(errors.New("source response exceeds size limit")) != "response_size_limit" {
		t.Fatal("size limit must be diagnosable")
	}
	if auditFailureCode(errors.New("fictional URL with secret query")) != "source_audit_rejected" {
		t.Fatal("raw errors must stay private")
	}
}
