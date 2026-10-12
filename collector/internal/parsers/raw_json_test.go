package parsers

import (
	"encoding/json"
	"github.com/dasudas/lianxibu-site/collector/internal/fetcher"
	"strings"
	"testing"
)

func TestPinnedMetricJsonUsesTextTransportAndStillRejectsNonJson(t *testing.T) {
	h := HLE()
	c := hleClaim()
	cursor, _ := json.Marshal(hleCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: 1, Revision: strings.Repeat("a", 40)})
	req, err := h.Request(c, string(cursor))
	if err != nil || req.Format != fetcher.Text {
		t.Fatal("raw file media type", err)
	}
	if _, err = h.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte("not JSON")}); err == nil {
		t.Fatal("text transport skipped JSON validation")
	}
	s := SkillsBench()
	c = skillsbenchClaim()
	owners := map[string]skillsbenchOwner{}
	for _, agent := range skillsbenchAgents {
		owners[agent] = skillsbenchOwner{ID: "42", Hash: strings.Repeat("b", 64)}
	}
	cursor, _ = json.Marshal(skillsbenchCursor{Run: c.Run.RunID, Source: c.Run.SourceID, Config: 1, Revision: strings.Repeat("a", 40), Updated: "2026-01-01T00:00:00Z", Step: 4, Owners: owners})
	req, err = s.Request(c, string(cursor))
	if err != nil || req.Format != fetcher.Text {
		t.Fatal("raw file media type", err)
	}
	if _, err = s.Parse(c, fetcher.Result{StatusCode: 200, FinalURL: req.URL, Body: []byte("not JSON")}); err == nil {
		t.Fatal("text transport skipped JSON validation")
	}
}
