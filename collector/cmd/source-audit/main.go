// Read-only source adapter audit. Input is a host-owned RunClaim on stdin; output
// contains hashes and evidence references, never the collected document bodies.
package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/parsers"
	"github.com/dasudas/lianxibu-site/collector/internal/runner"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"io"
	"os"
	"path/filepath"
	"time"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "source adapter audit failed")
		os.Exit(1)
	}
}
func run() (failure error) {
	raw, err := io.ReadAll(io.LimitReader(os.Stdin, 65537))
	if err != nil || len(raw) > 65536 {
		return fmt.Errorf("input unavailable")
	}
	if _, err = client.ParseStrict(raw); err != nil {
		return err
	}
	var claim client.RunClaim
	if err = json.Unmarshal(raw, &claim); err != nil {
		return err
	}
	reviews, err := loadAuditReviews(os.Getenv("SOURCE_AUDIT_LICENSE_REVIEWS"))
	if err != nil {
		return err
	}
	a, err := runner.AuditAdapter(claim.Config.Config.ParserVersion, reviews)
	if err != nil {
		return err
	}
	f, err := fetcher.NewPersistent(os.Getenv("SOURCE_AUDIT_BUDGET"))
	if err != nil {
		return err
	}
	defer f.Close()
	cursor := ""
	count := 0
	checks := []any{}
	evidence := []any{}
	gaps := []string{}
	windowComplete := false
	stage := "request"
	defer func() {
		result := map[string]any{"source_id": claim.Run.SourceID, "parser_version": claim.Config.Config.ParserVersion, "checked_at": time.Now().UTC(), "valid_samples": count, "adapter_roundtrip": failure == nil, "source_window_complete": windowComplete, "stage": stage, "pages": checks, "evidence": evidence, "gaps": gaps, "production_run": false}
		if failure != nil {
			result["failure_code"] = auditFailureCode(failure)
		}
		if errors.Is(failure, fetcher.ErrHostDeferred) {
			result["failure_code"] = "host_budget_deferred"
			var deferred *fetcher.HostDeferredError
			if errors.As(failure, &deferred) {
				result["retry_at"] = deferred.RetryAt
			}
		}
		_ = json.NewEncoder(os.Stdout).Encode(result)
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	for page := 0; page < 40; page++ {
		stage = "request"
		request, err := a.Request(claim, cursor)
		if err != nil {
			return err
		}
		if optional, ok := a.(scheduler.NotFoundAdapter); ok {
			request.AllowNotFound = optional.AllowNotFound()
		}
		stage = "fetch"
		response, err := f.Fetch(ctx, request)
		for errors.Is(err, fetcher.ErrHostDeferred) {
			wait, short := shortBudgetWait(err, time.Now())
			if !short {
				return err
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(wait):
			}
			response, err = f.Fetch(ctx, request)
		}
		if err != nil {
			return err
		}
		hash := sha256.Sum256(response.Body)
		checks = append(checks, map[string]any{"url": request.URL, "final_url": response.FinalURL, "status": response.StatusCode, "sha256": hex.EncodeToString(hash[:])})
		stage = "parse"
		parsed, err := a.Parse(claim, response)
		if err != nil {
			return err
		}
		gaps = parsed.Gaps
		for _, item := range parsed.Items {
			stage = "freeze"
			frozen, err := a.Freeze(claim, item, time.Now())
			if err != nil {
				return err
			}
			if _, err = client.DecodeObservation(frozen.Payload); err != nil {
				return err
			}
			var payload map[string]any
			if err = json.Unmarshal(item.Payload, &payload); err != nil {
				return err
			}
			evidence = append(evidence, map[string]any{"item_id": item.SourceItemID, "version": item.Version, "evidence_ref": item.EvidenceRef, "evidence": payload["evidence"]})
			count++
		}
		cursor = parsed.NextCursor
		windowComplete = parsed.End
		if parsed.End || count >= 3 {
			stage = "complete"
			return nil
		}
	}
	return fmt.Errorf("page limit exceeded")
}

func shortBudgetWait(err error, now time.Time) (time.Duration, bool) {
	return fetcher.ShortBudgetDelay(err, now)
}

// Only fixed classifications cross the audit boundary; wrapped network errors may contain URLs.
func auditFailureCode(err error) string {
	if errors.Is(err, context.DeadlineExceeded) {
		return "deadline_exceeded"
	}
	if errors.Is(err, fetcher.ErrHostBudgetUnavailable) {
		return "host_budget_unavailable"
	}
	switch err.Error() {
	case "source response exceeds size limit":
		return "response_size_limit"
	case "source content type is not an allowed text representation":
		return "response_media_type"
	case "source host is not allowed":
		return "redirect_host_denied"
	default:
		return "source_audit_rejected"
	}
}

func loadAuditReviews(location string) (parsers.LicenseReviews, error) {
	if location == "" {
		return parsers.LicenseReviews{}, nil
	}
	if !filepath.IsAbs(location) {
		return parsers.LicenseReviews{}, errors.New("audit review path invalid")
	}
	info, err := os.Lstat(location)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 65536 {
		return parsers.LicenseReviews{}, errors.New("audit reviews unavailable")
	}
	raw, err := os.ReadFile(location)
	if err != nil {
		return parsers.LicenseReviews{}, errors.New("audit reviews unavailable")
	}
	return parsers.ParseLicenseReviews(raw)
}
