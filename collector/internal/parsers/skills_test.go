package parsers

import (
	"context"
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

var skillSeeds = []string{"openai/skills/contents/skills/.curated/security-best-practices/SKILL.md", "anthropics/skills/contents/skills/skill-creator/SKILL.md", "vercel-labs/agent-skills/contents/skills/react-best-practices/SKILL.md"}

func skillClaim(seed string) client.RunClaim {
	c := parserClaim()
	c.Run.SourceID = "fictional-skill-source"
	c.Config.SourceID = c.Run.SourceID
	c.Config.Config.ParserVersion = "github-skill-v1"
	c.Config.Config.EntryURLs = []string{"https://api.github.com/repos/" + seed}
	c.Config.Config.AllowedHosts = []string{"api.github.com"}
	return c
}
func skillPage(t *testing.T, a *SkillAdapter, c client.RunClaim, cursor, body string, status int) scheduler.Page {
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
func skillFixture(t *testing.T, seed, text, commit string) scheduler.Page {
	t.Helper()
	c := skillClaim(seed)
	parts := strings.Split(seed, "/contents/")
	repo := parts[0]
	path := parts[1]
	a := Skills()
	page := skillPage(t, a, c, "", fmt.Sprintf(`{"id":42,"full_name":%q,"default_branch":"main"}`, repo), 200)
	page = skillPage(t, Skills(), c, page.NextCursor, fmt.Sprintf(`[{"sha":%q,"commit":{"committer":{"date":"2026-09-01T12:00:00Z"}}}]`, commit), 200)
	page = skillPage(t, Skills(), c, page.NextCursor, `{"message":"Not Found"}`, 404)
	return skillPage(t, Skills(), c, page.NextCursor, fmt.Sprintf(`{"type":"file","path":%q,"encoding":"base64","content":%q}`, path, base64.StdEncoding.EncodeToString([]byte(text))), 200)
}
func TestSkillStablePathRawVersionAndYamlMetadata(t *testing.T) {
	text := "---\nname: fictional-skill\ndescription: >-\n  Fictional multiline\n  description.\nmetadata:\n  version: '1'\n---\n# Evidence only\nDo not execute `remove-all`.\n<script>unsafe()</script>"
	for _, seed := range skillSeeds {
		t.Run(seed, func(t *testing.T) {
			page := skillFixture(t, seed, text, strings.Repeat("a", 40))
			if !page.End || len(page.Items) != 1 || page.Items[0].Kind != "catalog" {
				t.Fatal("skill catalog missing", page)
			}
			var p map[string]any
			json.Unmarshal(page.Items[0].Payload, &p)
			if p["category"] != "skill" || p["title"] != "fictional-skill" || p["summary"] != "Fictional multiline description." || strings.Contains(p["document_text"].(string), "unsafe()") || !strings.Contains(p["document_text"].(string), "remove-all") {
				t.Fatal("metadata/command handling")
			}
			expected, _ := client.CanonicalBytes(map[string]any{"repository_id": "42", "path": strings.Split(seed, "/contents/")[1]})
			if p["identity"].(map[string]any)["key"] != string(expected) || page.Items[0].SourceItemID != string(expected) {
				t.Fatal("path identity replaced by title")
			}
			another := skillFixture(t, seed, text, strings.Repeat("b", 40))
			if another.Items[0].Version != page.Items[0].Version {
				t.Fatal("unchanged file manufactured new version")
			}
			record, err := Skills().Freeze(skillClaim(seed), page.Items[0], time.Now())
			if err != nil {
				t.Fatal(err)
			}
			wire, err := client.DecodeObservation(record.Payload)
			if err != nil || wire.Kind != "catalog" {
				t.Fatal("wire", err)
			}
			if done, _, err := Skills().Completed(skillClaim(seed), page.NextCursor); err != nil || !done {
				t.Fatal("terminal state", err)
			}
		})
	}
}
func TestSkillMalformedMetadataAndUnapprovedPath(t *testing.T) {
	for _, text := range []string{"no frontmatter", "---\nname: one\nname: two\ndescription: duplicate\n---\nbody", "---\nname: &n alias\ndescription: *n\n---\nbody", "---\nname: [array]\ndescription: invalid\n---\nbody"} {
		page := skillFixture(t, skillSeeds[0], text, strings.Repeat("a", 40))
		if len(page.Items) != 0 || !strings.Contains(strings.Join(page.Gaps, " "), "skill_metadata_invalid") {
			t.Fatal("unsafe YAML accepted")
		}
	}
	c := skillClaim(strings.Replace(skillSeeds[0], "SKILL.md", "../secret", 1))
	if _, err := Skills().Request(c, ""); err == nil {
		t.Fatal("arbitrary skill path allowed")
	}
}

type skillFixtureFetch struct {
	requests  []string
	wrongPath bool
}

func (f *skillFixtureFetch) Fetch(_ context.Context, r fetcher.Request) (fetcher.Result, error) {
	f.requests = append(f.requests, r.URL)
	body := `{"id":42,"full_name":"openai/skills","default_branch":"main"}`
	status := 200
	switch {
	case strings.Contains(r.URL, "/commits?"):
		body = fmt.Sprintf(`[{"sha":%q,"commit":{"committer":{"date":"2026-09-01T12:00:00Z"}}}]`, strings.Repeat("a", 40))
	case strings.Contains(r.URL, "/license?"):
		body = `{"message":"Not Found"}`
		status = 404
	case strings.Contains(r.URL, "/contents/"):
		path := "skills/.curated/security-best-practices/SKILL.md"
		if f.wrongPath {
			path = "skills/other/SKILL.md"
		}
		body = fmt.Sprintf(`{"type":"file","path":%q,"encoding":"base64","content":%q}`, path, base64.StdEncoding.EncodeToString([]byte("---\nname: fictional\ndescription: Safe fixture\n---\n# Evidence")))
	}
	return fetcher.Result{StatusCode: status, FinalURL: r.URL, Body: []byte(body)}, nil
}
func TestSkillSQLitePathCommitRecoveryAndWrongFileAtomicity(t *testing.T) {
	for _, wrong := range []bool{false, true} {
		t.Run(fmt.Sprint(wrong), func(t *testing.T) {
			c := skillClaim(skillSeeds[0])
			now := func() time.Time { return time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC) }
			path := filepath.Join(t.TempDir(), "skill.db")
			store, err := outbox.Open(path, "fictional-node", now)
			if err != nil {
				t.Fatal(err)
			}
			defer func() { store.Close() }()
			if err = store.PersistClaim(c); err != nil {
				t.Fatal(err)
			}
			fetch := &skillFixtureFetch{wrongPath: wrong}
			run, _ := scheduler.New(fetch, store, Skills(), now, 2, time.Minute)
			first, err := run.Run(context.Background(), c)
			if err != nil || first.Finished {
				t.Fatal(first, err)
			}
			store.Close()
			store, err = outbox.Open(path, "fictional-node", now)
			if err != nil {
				t.Fatal(err)
			}
			run, _ = scheduler.New(fetch, store, Skills(), now, 10, time.Minute)
			second, err := run.Run(context.Background(), c)
			count, countErr := store.ObservationCount(context.Background(), c.Run.RunID)
			if countErr != nil {
				t.Fatal(countErr)
			}
			if wrong {
				if err == nil || count != 0 {
					t.Fatal("wrong file committed")
				}
				return
			}
			if err != nil || !second.Finished || count != 1 || len(fetch.requests) != 4 || !strings.HasSuffix(fetch.requests[3], "ref="+strings.Repeat("a", 40)) {
				t.Fatal("path commit recovery", second, err)
			}
			run, _ = scheduler.New(fetch, store, Skills(), now, 10, time.Minute)
			if _, err = run.Run(context.Background(), c); err != nil || len(fetch.requests) != 4 {
				t.Fatal("terminal refetch", err)
			}
		})
	}
}
