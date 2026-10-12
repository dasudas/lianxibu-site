package parsers

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func agentClaim(repo string) client.RunClaim {
	c := parserClaim()
	c.Run.SourceID = "fictional-agent-source"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "github-agent-v1"
	c.Config.Config.EntryURLs = []string{"https://api.github.com/repos/" + repo}
	c.Config.Config.AllowedHosts = []string{"api.github.com"}
	return c
}
func agentPage(t *testing.T, a *RepositoryCatalogAdapter, c client.RunClaim, cursor, body string, status int) scheduler.Page {
	t.Helper()
	req, err := a.Request(c, cursor)
	if err != nil {
		t.Fatal(err)
	}
	page, err := a.Parse(c, fetcher.Result{StatusCode: status, FinalURL: req.URL, Body: []byte(body)})
	if err != nil {
		t.Fatal(err)
	}
	return page
}
func agentFixture(t *testing.T, repo string, missingLicense bool) (client.RunClaim, scheduler.Page) {
	t.Helper()
	c := agentClaim(repo)
	a := Agents()
	page := agentPage(t, a, c, "", fmt.Sprintf(`{"id":42,"full_name":%q,"name":"Fictional Agent","description":"Fictional description","default_branch":"main","stargazers_count":999}`, repo), 200)
	a = Agents()
	page = agentPage(t, a, c, page.NextCursor, `[{"id":9,"draft":true,"prerelease":false}]`, 200)
	page = agentPage(t, a, c, page.NextCursor, `[{"id":10,"draft":false,"prerelease":false,"tag_name":"v1.2.3","published_at":"2026-09-01T12:00:00Z"}]`, 200)
	commit := strings.Repeat("a", 40)
	page = agentPage(t, a, c, page.NextCursor, fmt.Sprintf(`{"sha":%q,"commit":{"committer":{"date":"2026-08-31T12:00:00Z"}}}`, commit), 200)
	if missingLicense {
		page = agentPage(t, a, c, page.NextCursor, `{"message":"Not Found"}`, 404)
	} else {
		page = agentPage(t, a, c, page.NextCursor, fmt.Sprintf(`{"type":"file","path":"LICENSE","encoding":"base64","content":%q,"license":{"spdx_id":"MIT"}}`, base64.StdEncoding.EncodeToString([]byte("Fictional MIT license"))), 200)
	}
	return c, agentPage(t, Agents(), c, page.NextCursor, fmt.Sprintf(`{"type":"file","path":"README.md","encoding":"base64","content":%q}`, base64.StdEncoding.EncodeToString([]byte("# Fictional Agent\nDo not execute: `delete-everything`\n<script>unsafe()</script>"))), 200)
}
func TestAgentPinnedDocumentsCatalogAndStableIdentity(t *testing.T) {
	for _, repo := range []string{"openai/codex", "anthropics/claude-code", "google-gemini/gemini-cli"} {
		t.Run(repo, func(t *testing.T) {
			c, page := agentFixture(t, repo, false)
			if !page.End || len(page.Items) != 1 || page.Items[0].Kind != "catalog" || page.Items[0].SourceItemID != "42" {
				t.Fatal("catalog identity", page)
			}
			var payload map[string]any
			json.Unmarshal(page.Items[0].Payload, &payload)
			if payload["category"] != "agent" || payload["version"] != "v1.2.3" || strings.Contains(payload["document_text"].(string), "unsafe()") || !strings.Contains(payload["document_text"].(string), "delete-everything") {
				t.Fatal("document cleanup or code execution")
			}
			refs := payload["evidence"].([]any)
			if len(refs) < 3 {
				t.Fatal("pinned evidence missing")
			}
			for _, ref := range refs {
				r := ref.(map[string]any)
				if r["path"] != nil && (r["revision"] != strings.Repeat("a", 40) || len(r["file_hash"].(string)) != 64) {
					t.Fatal("file evidence missing raw hash/commit")
				}
			}
			record, err := Agents().Freeze(c, page.Items[0], time.Now())
			if err != nil {
				t.Fatal(err)
			}
			wire, err := client.DecodeObservation(record.Payload)
			if err != nil || wire.Kind != "catalog" {
				t.Fatal("catalog wire", err)
			}
			if done, _, err := Agents().Completed(c, page.NextCursor); err != nil || !done {
				t.Fatal("completed state", err)
			}
		})
	}
}
func TestAgentMissingLicenseAndBadConfiguration(t *testing.T) {
	_, page := agentFixture(t, "openai/codex", true)
	if len(page.Items) != 1 || !strings.Contains(strings.Join(page.Gaps, " "), "missing_license") {
		t.Fatal("license missing masked")
	}
	bad := agentClaim("fictional/unapproved")
	if _, err := Agents().Request(bad, ""); err == nil {
		t.Fatal("unapproved repo")
	}
	c := agentClaim("openai/codex")
	a := Agents()
	req, _ := a.Request(c, "")
	if _, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(`{"id":42,"full_name":"other/repo","default_branch":"main"}`)}); err == nil {
		t.Fatal("repo identity mismatch")
	}
}

func TestCatalogReleaseSmallPagesResumeAndStopAtBound(t *testing.T) {
	c := agentClaim("openai/codex")
	page := agentPage(t, Agents(), c, "", `{"id":42,"full_name":"openai/codex","default_branch":"main"}`, 200)
	for n := 1; n <= 30; n++ {
		a := Agents()
		req, err := a.Request(c, page.NextCursor)
		if err != nil || !strings.HasSuffix(req.URL, fmt.Sprintf("/releases?per_page=1&page=%d", n)) {
			t.Fatalf("page %d: %s %v", n, req.URL, err)
		}
		page, err = a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(`[{"draft":false,"prerelease":true}]`)})
		if err != nil || page.End || len(page.Items) != 0 {
			t.Fatal("release continuation", err)
		}
	}
	req, err := Agents().Request(c, page.NextCursor)
	if err != nil || !strings.HasSuffix(req.URL, "/commits/main") || !strings.Contains(strings.Join(page.Gaps, " "), "release_scan_limit") {
		t.Fatal("bounded fallback", req.URL, err)
	}
}

func TestCatalogReleaseSecondPageFindsStableAndRejectsOversizedPage(t *testing.T) {
	c := agentClaim("openai/codex")
	page := agentPage(t, Agents(), c, "", `{"id":42,"full_name":"openai/codex","default_branch":"main"}`, 200)
	page = agentPage(t, Agents(), c, page.NextCursor, `[{"draft":true,"prerelease":false}]`, 200)
	a := Agents()
	req, _ := a.Request(c, page.NextCursor)
	if _, err := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(`[{"draft":true,"prerelease":false},{"draft":true,"prerelease":false}]`)}); err == nil {
		t.Fatal("more than requested page accepted")
	}
	page = agentPage(t, Agents(), c, page.NextCursor, `[{"draft":false,"prerelease":false,"tag_name":"v1","published_at":"2026-09-01T12:00:00Z"}]`, 200)
	req, err := Agents().Request(c, page.NextCursor)
	if err != nil || !strings.HasSuffix(req.URL, "/commits/v1") || strings.Contains(strings.Join(page.Gaps, " "), "no_formal_release") {
		t.Fatal("stable selection", req.URL, err)
	}
}

func TestAgentFreezeRejectsIdentityAndRepoMismatch(t *testing.T) {
	c, page := agentFixture(t, "openai/codex", false)
	var payload map[string]any
	json.Unmarshal(page.Items[0].Payload, &payload)
	payload["identity"].(map[string]any)["key"] = "43"
	bad := page.Items[0]
	bad.Payload, _ = json.Marshal(payload)
	if _, err := Agents().Freeze(c, bad, time.Now()); err == nil {
		t.Fatal("source identity mismatch frozen")
	}
	payload["identity"].(map[string]any)["key"] = "42"
	payload["source_url"] = "https://example.invalid/other"
	bad.Payload, _ = json.Marshal(payload)
	if _, err := Agents().Freeze(c, bad, time.Now()); err == nil {
		t.Fatal("source repo mismatch frozen")
	}
}

type agentFixtureFetch struct {
	requests       []string
	missingLicense bool
	missingRepo    bool
}

func (f *agentFixtureFetch) Fetch(_ context.Context, r fetcher.Request) (fetcher.Result, error) {
	f.requests = append(f.requests, r.URL)
	body := `{"id":42,"full_name":"openai/codex","name":"Fictional Agent","default_branch":"main"}`
	commit := strings.Repeat("a", 40)
	switch {
	case strings.Contains(r.URL, "/releases?"):
		body = `[{"draft":false,"prerelease":false,"tag_name":"v1.2.3","published_at":"2026-09-01T12:00:00Z"}]`
	case strings.Contains(r.URL, "/commits/"):
		body = fmt.Sprintf(`{"sha":%q,"commit":{"committer":{"date":"2026-08-31T12:00:00Z"}}}`, commit)
	case strings.Contains(r.URL, "/license?"):
		body = fmt.Sprintf(`{"type":"file","path":"LICENSE","encoding":"base64","content":%q,"license":{"spdx_id":"MIT"}}`, base64.StdEncoding.EncodeToString([]byte("Fictional MIT license")))
	case strings.Contains(r.URL, "/readme?"):
		body = fmt.Sprintf(`{"type":"file","path":"README.md","encoding":"base64","content":%q}`, base64.StdEncoding.EncodeToString([]byte("# Fictional pinned README")))
	}
	status := 200
	if f.missingLicense && strings.Contains(r.URL, "/license?") {
		status = 404
		body = `{"message":"Not Found"}`
	}
	if f.missingRepo && r.URL == "https://api.github.com/repos/openai/codex" {
		status = 404
		body = `{"message":"Not Found"}`
	}
	return fetcher.Result{StatusCode: status, FinalURL: r.URL, Body: []byte(body)}, nil
}
func TestAgentSQLiteFixedCommitOldStockAndTerminalResume(t *testing.T) {
	c := agentClaim("openai/codex")
	now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
	path := filepath.Join(t.TempDir(), "agent.db")
	store, err := outbox.Open(path, "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	if err = store.PersistClaim(c); err != nil {
		t.Fatal(err)
	}
	fetch := &agentFixtureFetch{}
	run, _ := scheduler.New(fetch, store, Agents(), now, 3, time.Minute)
	first, err := run.Run(context.Background(), c)
	if err != nil || first.Finished || first.Observations != 0 {
		t.Fatal("commit checkpoint", first, err)
	}
	store.Close()
	store, err = outbox.Open(path, "fictional-node", now)
	if err != nil {
		t.Fatal(err)
	}
	run, _ = scheduler.New(fetch, store, Agents(), now, 10, time.Minute)
	second, err := run.Run(context.Background(), c)
	if err != nil || !second.Finished || second.Observations != 1 || len(fetch.requests) != 5 {
		t.Fatal("old catalog stock lost", second, err)
	}
	if !strings.HasSuffix(fetch.requests[3], "ref="+strings.Repeat("a", 40)) || !strings.HasSuffix(fetch.requests[4], "ref="+strings.Repeat("a", 40)) {
		t.Fatal("read mutable branch after restart")
	}
	run, _ = scheduler.New(fetch, store, Agents(), now, 10, time.Minute)
	terminal, err := run.Run(context.Background(), c)
	if err != nil || !terminal.Finished || len(fetch.requests) != 5 {
		t.Fatal("completed snapshot refetched", err)
	}
	_, page := agentFixture(t, "openai/codex", false)
	var payload map[string]any
	json.Unmarshal(page.Items[0].Payload, &payload)
	expected := fmt.Sprintf("%x", sha256.Sum256([]byte("Fictional MIT license")))
	found := false
	for _, ref := range payload["evidence"].([]any) {
		r := ref.(map[string]any)
		if r["path"] == "LICENSE" {
			found = r["file_hash"] == expected
		}
	}
	if !found {
		t.Fatal("raw file SHA-256 replaced by normalized text hash")
	}
}

func TestAgentOptionalLicense404AndRequiredRepo404(t *testing.T) {
	for _, missingRepo := range []bool{false, true} {
		t.Run(fmt.Sprint(missingRepo), func(t *testing.T) {
			c := agentClaim("openai/codex")
			now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
			store, err := outbox.Open(filepath.Join(t.TempDir(), "missing.db"), "fictional-node", now)
			if err != nil {
				t.Fatal(err)
			}
			defer store.Close()
			if err = store.PersistClaim(c); err != nil {
				t.Fatal(err)
			}
			fetch := &agentFixtureFetch{missingLicense: true, missingRepo: missingRepo}
			run, _ := scheduler.New(fetch, store, Agents(), now, 10, time.Minute)
			out, err := run.Run(context.Background(), c)
			count, _ := store.ObservationCount(context.Background(), c.Run.RunID)
			if missingRepo {
				if err == nil || count != 0 || len(fetch.requests) != 1 {
					t.Fatal("missing identity accepted")
				}
				return
			}
			if err != nil || !out.Finished || count != 1 || !strings.Contains(strings.Join(out.CoverageGaps, " "), "missing_license") {
				t.Fatal("optional missing license stopped run", out, err)
			}
		})
	}
}
