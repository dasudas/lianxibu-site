package parsers

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"strings"
	"testing"
	"time"
)

func TestHLERejectsInvalidSourceMetadataAndForeignCursor(t *testing.T) {
	c := hleClaim()
	for _, body := range []string{`{"id":"other/data","sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","gated":false,"siblings":[{"rfilename":"leaderboard.json"}]}`, `{"id":"OpenEvals/leaderboard-data","sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","gated":"auto","siblings":[{"rfilename":"leaderboard.json"}]}`, `{"id":"OpenEvals/leaderboard-data","sha":"main","gated":false,"siblings":[{"rfilename":"leaderboard.json"}]}`} {
		a := HLE()
		req, _ := a.Request(c, "")
		if _, e := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte(body)}); e == nil {
			t.Fatal("unverified snapshot")
		}
	}
	state := hleCursor{Run: "foreign", Source: c.Run.SourceID, Config: c.Run.ConfigVersion, Revision: strings.Repeat("a", 40), Gaps: []string{}}
	raw, _ := json.Marshal(state)
	if _, e := HLE().Request(c, string(raw)); e == nil {
		t.Fatal("foreign cursor")
	}
}
func TestHLEFileEvidenceAndIdentityIsolation(t *testing.T) {
	c := hleClaim()
	rev := strings.Repeat("a", 40)
	cursor, _ := json.Marshal(hleCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: c.Run.ConfigVersion, Revision: rev, Gaps: []string{}})
	body := hleDocument(2)
	body = []byte(strings.Replace(string(body), "fictional/Model-01", "unqualified-slug", 1))
	a := HLE()
	req, _ := a.Request(c, string(cursor))
	page, e := a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: body})
	if e != nil || len(page.Items) != 1 || !strings.Contains(strings.Join(page.Gaps, " "), "model_identity_invalid") {
		t.Fatal(page, e)
	}
	parsed := map[string]any{}
	json.Unmarshal(page.Items[0].Payload, &parsed)
	ref := parsed["evidence"].([]any)[0].(map[string]any)
	hash := sha256.Sum256(body)
	if ref["file_hash"] != hex.EncodeToString(hash[:]) || ref["revision"] != rev || ref["path"] != "leaderboard.json" {
		t.Fatal("raw evidence not retained")
	}
	for _, replacement := range []string{"AccuracyX", "fictional/Model-00"} {
		bad := hleDocument(2)
		if replacement == "AccuracyX" {
			bad = []byte(strings.Replace(string(bad), "Accuracy", replacement, 1))
		} else {
			bad = []byte(strings.Replace(string(bad), "fictional/Model-01", replacement, 1))
		}
		req, _ = a.Request(c, string(cursor))
		if _, e = a.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: bad}); e == nil {
			t.Fatal("bad source", replacement)
		}
	}
	for _, field := range []string{"benchmark_version", "evidence"} {
		item := page.Items[0]
		if field == "benchmark_version" {
			parsed[field] = rev
		} else {
			parsed["benchmark_version"] = "aggregation_snapshot:" + rev
			ref["url"] = "https://example.invalid/fake.json"
		}
		item.Payload, _ = json.Marshal(parsed)
		if _, e = a.Freeze(c, item, time.Now()); e == nil {
			t.Fatal(fmt.Sprint("forged ", field))
		}
	}
}
