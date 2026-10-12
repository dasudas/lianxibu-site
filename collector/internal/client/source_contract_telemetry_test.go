package client

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"
)

func sourceContractExample(t *testing.T) []byte {
	t.Helper()
	raw, err := os.ReadFile("../../../contracts/source-contract-telemetry-v1.example.json")
	if err != nil {
		t.Fatal(err)
	}
	return raw
}
func TestSourceContractTelemetryGolden(t *testing.T) {
	raw := sourceContractExample(t)
	s, err := DecodeSourceContractTelemetry(raw)
	if err != nil {
		t.Fatal(err)
	}
	if s.PayloadHash != "b7d9a4d28ce1c155b8d36a5b3c8198ec41cb809b132e85be86d9bff06e7fdda5" || s.TotalFailures != 1 || len(s.Events) != 1 {
		t.Fatal("original canonical identity")
	}
	frozen := s.FrozenWireBytes()
	s.SourceID = "fictional-mutated"
	s.Events[0].Sequence = 2
	if !bytes.Equal(frozen, s.FrozenWireBytes()) {
		t.Fatal("fields altered original wire")
	}
	frozen[0] = '!'
	if s.FrozenWireBytes()[0] != '{' {
		t.Fatal("wire aliased")
	}
	reordered := append([]byte("\n "), raw...)
	again, err := DecodeSourceContractTelemetry(reordered)
	if err != nil || again.PayloadHash != s.PayloadHash {
		t.Fatal("whitespace changed identity")
	}
	if strings.Contains(fmt.Sprint(s), "fictional-mutated") {
		t.Fatal("diagnostic disclosure")
	}
}
func TestSourceContractTelemetryClosedFailures(t *testing.T) {
	raw := sourceContractExample(t)
	cases := map[string]func(map[string]any){
		"missing": func(m map[string]any) { delete(m, "events") }, "unknown": func(m map[string]any) { m["password"] = "fictional-secret" }, "null": func(m map[string]any) { m["events"] = nil },
		"schema": func(m map[string]any) { m["schema_version"] = "v2" }, "zero version": func(m map[string]any) { m["config_version"] = 0 }, "zero sequence": func(m map[string]any) { m["sequence"] = 0 }, "unsafe": func(m map[string]any) { m["sequence"] = uint64(9007199254740992) },
		"string integer": func(m map[string]any) { m["total_failures"] = "1" }, "epoch": func(m map[string]any) { m["epoch"] = "A0000000-0000-4000-8000-000000000001" }, "control ID": func(m map[string]any) { m["source_id"] = "fictional\nsource" }, "wide ID": func(m map[string]any) { m["source_id"] = strings.Repeat("测", 81) },
		"millisecond": func(m map[string]any) { m["observed_at"] = "2026-01-01T00:10:00.000Z" }, "offset": func(m map[string]any) { m["observed_at"] = "2026-01-01T00:10:00.000000+00:00" }, "past epoch": func(m map[string]any) { m["window_start"] = "1969-12-31T23:59:59.000000Z" }, "window": func(m map[string]any) { m["window_start"] = "2026-01-01T00:00:01.000000Z" },
		"null event": func(m map[string]any) { m["events"] = []any{nil} }, "extra event": func(m map[string]any) { m["events"].([]any)[0].(map[string]any)["raw_page"] = "fictional-private" }, "zero event": func(m map[string]any) { m["events"].([]any)[0].(map[string]any)["sequence"] = 0 }, "over total": func(m map[string]any) { m["total_failures"] = 0 },
		"exclusive start": func(m map[string]any) { m["events"].([]any)[0].(map[string]any)["occurred_at"] = m["window_start"] }, "future event": func(m map[string]any) {
			m["events"].([]any)[0].(map[string]any)["occurred_at"] = "2026-01-01T00:10:00.000001Z"
		},
		"duplicate sequence": func(m map[string]any) { e := m["events"].([]any)[0]; m["events"] = []any{e, e} }, "backwards time": func(m map[string]any) {
			m["total_failures"] = 2
			m["events"] = append(m["events"].([]any), map[string]any{"sequence": 2, "occurred_at": "2026-01-01T00:08:00.000000Z"})
		},
		"capacity": func(m map[string]any) {
			m["total_failures"] = 201
			events := make([]any, 201)
			for i := range events {
				events[i] = map[string]any{"sequence": i + 1, "occurred_at": "2026-01-01T00:09:00.000000Z"}
			}
			m["events"] = events
		},
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			m := map[string]any{}
			if err := json.Unmarshal(raw, &m); err != nil {
				t.Fatal(err)
			}
			change(m)
			input, err := json.Marshal(m)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = DecodeSourceContractTelemetry(input); err != ErrSourceContractTelemetry {
				t.Fatal("must reject with fixed error")
			}
		})
	}
}
func TestSourceContractTelemetryRawFailures(t *testing.T) {
	for _, raw := range [][]byte{nil, []byte(`{"source_id":"fictional","source_id":"duplicate"}`), append([]byte{0xef, 0xbb, 0xbf}, sourceContractExample(t)...), append(sourceContractExample(t), []byte("{}")...), bytes.Repeat([]byte(" "), 32769), []byte(`{"sequence":1e0}`), []byte(`{"sequence":1.0}`), []byte(`{"source_id":"\ud800"}`)} {
		if _, err := DecodeSourceContractTelemetry(raw); err != ErrSourceContractTelemetry {
			t.Fatal("strict raw rejection")
		}
	}
}
func TestSourceContractTelemetryCompleteZeroAndBoundaries(t *testing.T) {
	at := time.Date(2026, 1, 1, 0, 10, 0, 0, time.UTC)
	epoch := "00000000-0000-4000-8000-000000000001"
	s, err := NewSourceContractTelemetry("fictional-source", 1, epoch, 1, at, 0, []SourceFailureEvent{})
	if err != nil || s.TotalFailures != 0 || len(s.Events) != 0 {
		t.Fatal("complete zero")
	}
	if _, err = NewSourceContractTelemetry("fictional-source", 1, epoch, 1, at, 0, nil); err == nil {
		t.Fatal("nil is not zero evidence")
	}
	if _, err = NewSourceContractTelemetry("fictional-source", 1, epoch, 1, at.Add(time.Nanosecond), 0, []SourceFailureEvent{}); err == nil {
		t.Fatal("precision")
	}
	events := make([]SourceFailureEvent, 200)
	for i := range events {
		events[i] = SourceFailureEvent{uint64(i + 1), at.Format(SourceContractTime)}
	}
	if _, err = NewSourceContractTelemetry(strings.Repeat("测", 80), 9007199254740991, epoch, 9007199254740991, at, 200, events); err != nil {
		t.Fatal("inclusive bounds", err)
	}
	if _, err = NewSourceContractTelemetry("fictional-source", 1, epoch, 2, at, 200, []SourceFailureEvent{}); err != nil {
		t.Fatal("all failures outside current window remain total", err)
	}
}
