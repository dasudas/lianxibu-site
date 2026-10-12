package parsers

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"strings"
	"testing"
)

func TestRepositoryNoAssertionRequiresExactReviewedFile(t *testing.T) {
	commit := strings.Repeat("a", 40)
	body := []byte("Fictional license requiring manual review")
	sum := sha256.Sum256(body)
	hash := hex.EncodeToString(sum[:])
	for _, match := range []bool{false, true} {
		a := Workflows()
		c := workflowClaim("bmad-code-org/BMAD-METHOD")
		approvedHash := hash
		if !match {
			approvedHash = strings.Repeat("0", 64)
		}
		raw := fmt.Sprintf(`[{"repository":"bmad-code-org/BMAD-METHOD","commit":%q,"path":"LICENSE","sha256":%q}]`, commit, approvedHash)
		reviews, err := ParseLicenseReviews([]byte(raw))
		if err != nil {
			t.Fatal(err)
		}
		a.SetLicenseReviews(reviews)
		p := agentPage(t, a, c, "", `{"id":42,"full_name":"bmad-code-org/BMAD-METHOD","name":"Fictional","default_branch":"main"}`, 200)
		p = agentPage(t, a, c, p.NextCursor, `[]`, 200)
		p = agentPage(t, a, c, p.NextCursor, fmt.Sprintf(`{"sha":%q,"commit":{"committer":{"date":"2026-09-01T12:00:00Z"}}}`, commit), 200)
		req, err := a.Request(c, p.NextCursor)
		if err != nil {
			t.Fatal(err)
		}
		license := fmt.Sprintf(`{"type":"file","path":"LICENSE","encoding":"base64","content":%q,"license":{"spdx_id":"NOASSERTION"}}`, base64.StdEncoding.EncodeToString(body))
		p, err = a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(license)})
		if !match && err == nil {
			t.Fatal("mismatched evidence accepted")
		}
		if match && (err != nil || strings.Contains(strings.Join(p.Gaps, " "), "missing_license")) {
			t.Fatal("exact reviewed license rejected", err)
		}
	}
}

func TestLicenseReviewOnlyMatchesExactRepositoryCommitPathAndBytes(t *testing.T) {
	commit, hash := strings.Repeat("a", 40), strings.Repeat("b", 64)
	raw := fmt.Sprintf(`[{"repository":"fictional/project","commit":%q,"path":"LICENSE","sha256":%q}]`, commit, hash)
	r, err := ParseLicenseReviews([]byte(raw))
	if err != nil {
		t.Fatal(err)
	}
	if !r.matches("fictional/project", commit, "LICENSE", hash) {
		t.Fatal("review lost")
	}
	for _, args := range [][4]string{{"fictional/other", commit, "LICENSE", hash}, {"fictional/project", strings.Repeat("c", 40), "LICENSE", hash}, {"fictional/project", commit, "other/LICENSE", hash}, {"fictional/project", commit, "LICENSE", strings.Repeat("d", 64)}} {
		if r.matches(args[0], args[1], args[2], args[3]) {
			t.Fatal("review escaped its scope")
		}
	}
	for _, bad := range []string{strings.Replace(raw, "LICENSE", "../LICENSE", 1), strings.Replace(raw, `"path":`, `"unknown":`, 1), raw + raw} {
		if _, err := ParseLicenseReviews([]byte(bad)); err == nil {
			t.Fatal("bad review accepted")
		}
	}
}
