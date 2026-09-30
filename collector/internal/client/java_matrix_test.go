package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"testing"
)

func interopJSON(t *testing.T, value any) []byte {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}
func interopHash(t *testing.T, domain HashDomain, value any) string {
	t.Helper()
	hash, err := Hash(domain, value)
	if err != nil {
		t.Fatal(err)
	}
	return hash
}
func interopRead(t *testing.T, c *Client, ctx context.Context, path string) map[string]any {
	t.Helper()
	raw, err := c.request(ctx, http.MethodGet, path, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	var row map[string]any
	if err := decodeData(raw, &row); err != nil {
		t.Fatal(err)
	}
	return row
}
func interopControl(t *testing.T, c *Client, ctx context.Context, run, op, key, token string, business map[string]any, revision any) (map[string]any, error) {
	t.Helper()
	path := "/runs/" + run + "/" + op
	method := http.MethodPut
	if op == "member_seal" {
		path = "/runs/" + run + "/members/" + business["member_id"].(string) + "/actions"
		method = http.MethodPost
	}
	raw, err := c.request(ctx, method, path, key, interopJSON(t, map[string]any{"business": business, "attempt": map[string]any{"claim_token": token, "expected_run_revision": revision}}))
	if err != nil {
		return nil, err
	}
	var result map[string]any
	if err := decodeData(raw, &result); err != nil {
		t.Fatal(err)
	}
	hash := interopHash(t, ControlBusinessHash, map[string]any{"operation": op, "business": business})
	if result["business_hash"] != hash || result["run_id"] != run || result["receipt_ref"] == "" {
		t.Fatal("Java control receipt identity mismatch")
	}
	return result, nil
}
func interopPart(t *testing.T, run string, members []any) map[string]any {
	return map[string]any{"run_id": run, "part_no": 0, "members": members, "part_hash": interopHash(t, ManifestPartHash, map[string]any{"run_id": run, "part_no": 0, "members": members})}
}
func interopResult(t *testing.T, run string, part map[string]any) map[string]any {
	count := len(part["members"].([]any))
	hash := interopHash(t, ManifestHash, map[string]any{"run_id": run, "expected_count": count, "parts": []any{map[string]any{"part_no": 0, "part_hash": part["part_hash"], "member_count": count}}})
	return map[string]any{"run_id": run, "manifest_hash": hash, "part_count": 1, "expected_count": count, "fetch_outcome": "succeeded", "coverage": map[string]any{"state": "complete", "missing_inputs": []any{}, "missing_dates": []any{}, "no_update_dates": []any{}}}
}
func interopObservation(t *testing.T, run, id string) map[string]any {
	raw, err := os.ReadFile(os.Getenv("V02_FROZEN_FILE"))
	if err != nil {
		t.Fatal(err)
	}
	value, err := ParseStrict(raw)
	if err != nil {
		t.Fatal(err)
	}
	row := value.(map[string]any)
	row["run_id"] = run
	row["observation_id"] = id
	row["member_id"] = id
	row["source_item_id"] = id
	delete(row, "payload_hash")
	row["payload_hash"] = interopHash(t, ObservationHash, row)
	return row
}

func runJavaMatrix(t *testing.T, c *Client, ctx context.Context, mode string) {
	token := strings.Repeat("C", 43)
	if mode != "matrix" {
		runJavaTakeover(t, c, ctx, mode, token)
		return
	}
	for _, scenario := range []struct {
		name              string
		accepted, pending int
		seal              bool
		state             string
	}{
		{"partial", 9, 1, true, "partial"}, {"pending", 0, 1, false, "waiting_items"}, {"excluded", 0, 1, true, "no_usable_input"}, {"empty", 0, 0, false, "empty_success"},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			run := "go-matrix-" + scenario.name
			members := []any{}
			for i := 0; i < scenario.accepted+scenario.pending; i++ {
				row := interopObservation(t, run, fmt.Sprintf("%s-%02d", scenario.name, i))
				members = append(members, map[string]any{"member_id": row["member_id"], "source_item_id": row["source_item_id"], "observation_id": row["observation_id"], "payload_hash": row["payload_hash"], "replaces_observation_id": nil})
				if i < scenario.accepted {
					raw, err := c.request(ctx, http.MethodPost, "/observations", "", interopJSON(t, map[string]any{"observations": []any{row}}))
					if err != nil {
						t.Fatal(err)
					}
					var data struct {
						Acks []ObservationAck `json:"acks"`
					}
					if err := decodeData(raw, &data); err != nil || len(data.Acks) != 1 || data.Acks[0].Status != "accepted" {
						t.Fatalf("observation refused: %v", err)
					}
				}
			}
			part := interopPart(t, run, members)
			result := interopResult(t, run, part)
			// Result intentionally precedes the complete manifest.
			rev := interopRead(t, c, ctx, "/runs/"+run)["run_revision"]
			if _, err := interopControl(t, c, ctx, run, "result", run+"-result", token, result, rev); err != nil {
				t.Fatal(err)
			}
			badPart := interopPart(t, run, members)
			badPart["part_hash"] = strings.Repeat("0", 64)
			if _, err := interopControl(t, c, ctx, run, "manifest", run+"-bad-hash", token, badPart, nil); err == nil {
				t.Fatal("bad part hash accepted")
			}
			if len(members) > 0 {
				repeated := interopPart(t, run, []any{members[0], members[0]})
				if _, err := interopControl(t, c, ctx, run, "manifest", run+"-duplicate", token, repeated, nil); err == nil {
					t.Fatal("duplicate member accepted")
				}
			}
			if _, err := interopControl(t, c, ctx, run, "manifest", run+"-part", token, part, nil); err != nil {
				t.Fatal(err)
			}
			if scenario.seal {
				member := members[len(members)-1].(map[string]any)["member_id"]
				business := map[string]any{"run_id": run, "member_id": member, "expected_member_revision": 1, "action": "seal", "reason": "fixture_unavailable"}
				rev = interopRead(t, c, ctx, "/runs/"+run)["run_revision"]
				if _, err := interopControl(t, c, ctx, run, "member_seal", run+"-stale-seal", token, business, rev); !errors.Is(err, ErrRunFrozen) {
					t.Fatalf("stale member CAS: %v", err)
				}
				business["expected_member_revision"] = 0
				if _, err := interopControl(t, c, ctx, run, "member_seal", run+"-seal", token, business, rev); err != nil {
					t.Fatal(err)
				}
			}
			state := interopRead(t, c, ctx, "/runs/"+run)
			excluded := 0
			if scenario.seal {
				excluded = scenario.pending
			}
			if state["delivery_state"] != scenario.state || state["accepted_count"] != float64(scenario.accepted) || state["excluded_count"] != float64(excluded) || state["pending_count"] != float64(scenario.pending-excluded) || state["expected_count"] != float64(len(members)) {
				t.Fatalf("incorrect matrix counts: %v", state)
			}
			if scenario.state != "waiting_items" {
				changed := interopPart(t, run, []any{})
				changed["part_no"] = 1
				changed["part_hash"] = interopHash(t, ManifestPartHash, map[string]any{"run_id": run, "part_no": 1, "members": []any{}})
				if _, err := interopControl(t, c, ctx, run, "manifest", run+"-late-part", token, changed, nil); !errors.Is(err, ErrRunFrozen) {
					t.Fatalf("terminal part mutation: %v", err)
				}
				row := interopObservation(t, run, scenario.name+"-late-unknown")
				raw, err := c.request(ctx, http.MethodPost, "/observations", "", interopJSON(t, map[string]any{"observations": []any{row}}))
				if err != nil {
					t.Fatal(err)
				}
				var data struct {
					Acks []ObservationAck `json:"acks"`
				}
				if err := decodeData(raw, &data); err != nil || len(data.Acks) != 1 || data.Acks[0].Status != "rejected" {
					t.Fatalf("unknown terminal observation accepted: %v", err)
				}
			}
		})
	}
	runJavaPayloadNegatives(t, c, ctx)
}

func runJavaPayloadNegatives(t *testing.T, c *Client, ctx context.Context) {
	for _, tc := range []struct {
		name, kind string
		status     int
		mutate     func(map[string]any)
	}{
		{"unknown-schema", "catalog", 409, func(p map[string]any) { p["schema_version"] = "99" }},
		{"unknown-field", "catalog", 400, func(p map[string]any) { p["extra"] = true }},
		{"nested-field", "catalog", 400, func(p map[string]any) { p["identity"].(map[string]any)["extra"] = true }},
		{"skill-namespace", "catalog", 422, func(p map[string]any) {
			p["category"] = "skill"
			p["identity"].(map[string]any)["namespace"] = "github_repository"
		}},
		{"text-limit", "catalog", 422, func(p map[string]any) { p["document_text"] = strings.Repeat("x", 128*1024+1) }},
		{"payload-limit", "catalog", 422, func(p map[string]any) { p["document_text"] = strings.Repeat("x", 201*1024) }},
		{"unknown-scale", "metric", 422, func(p map[string]any) { p["unit"] = "unknown" }},
		{"unknown-owner", "metric", 422, func(p map[string]any) { p["evaluation_scope"] = "unknown" }},
		{"skill-config-score", "metric", 422, func(p map[string]any) {
			p["evaluation_scope"] = "configuration"
			p["subject"] = map[string]any{"category": "skill", "identity": map[string]any{"namespace": "github_skill", "key": "fictional/skill"}}
			p["evaluation_config"].(map[string]any)["agent"] = "fictional-agent"
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			file := os.Getenv("V02_FROZEN_FILE")
			if tc.kind == "catalog" {
				file = os.Getenv("V02_CATALOG_FILE")
			}
			raw, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			value, err := ParseStrict(raw)
			if err != nil {
				t.Fatal(err)
			}
			row := value.(map[string]any)
			row["observation_id"] = "negative-" + tc.name
			row["member_id"] = "negative-" + tc.name
			row["source_item_id"] = "negative-" + tc.name
			tc.mutate(row["payload"].(map[string]any))
			delete(row, "payload_hash")
			row["payload_hash"] = interopHash(t, ObservationHash, row)
			if _, err := DecodeObservation(interopJSON(t, row)); err == nil {
				t.Fatal("Go accepted invalid payload")
			}
			request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+"/observations", bytes.NewReader(interopJSON(t, map[string]any{"observations": []any{row}})))
			if err != nil {
				t.Fatal(err)
			}
			request.Header.Set("Authorization", "Bearer "+c.token)
			request.Header.Set("Content-Type", "application/json")
			response, err := c.http.Do(request)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			if response.StatusCode != tc.status {
				t.Fatalf("expected HTTP %d, got %d", tc.status, response.StatusCode)
			}
			var envelope struct {
				Error struct {
					Code string `json:"code"`
				} `json:"error"`
			}
			if err := json.NewDecoder(response.Body).Decode(&envelope); err != nil {
				t.Fatal(err)
			}
			expected := map[int]string{400: "protocol_error", 409: "schema_not_supported", 422: "validation"}[tc.status]
			if envelope.Error.Code != expected {
				t.Fatalf("expected code %s, got %s", expected, envelope.Error.Code)
			}
		})
	}
}

func runJavaTakeover(t *testing.T, c *Client, ctx context.Context, mode, oldToken string) {
	run := "go-takeover"
	part := interopPart(t, run, []any{})
	result := interopResult(t, run, part)
	file := os.Getenv("V02_CLAIM_FILE") + ".control"
	if mode == "takeover-prepare" {
		// Freeze business and old attempt before Java expires this lease.
		frozen := map[string]any{"part": part, "result": result, "token": oldToken, "revision": interopRead(t, c, ctx, "/runs/"+run)["run_revision"]}
		if err := os.WriteFile(file, interopJSON(t, frozen), 0600); err != nil {
			t.Fatal(err)
		}
		return
	}
	raw, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := ParseStrict(raw)
	if err != nil {
		t.Fatal(err)
	}
	frozen := parsed.(map[string]any)
	part = frozen["part"].(map[string]any)
	result = frozen["result"].(map[string]any)
	claims, err := c.Claim(ctx, "go-takeover-claim", []string{"fixture:go"}, &memoryClaims{})
	if err != nil || len(claims) != 1 || claims[0].Run.RunID != run || claims[0].ClaimToken == nil {
		t.Fatalf("takeover claim: %v", err)
	}
	claim := claims[0]
	token := *claim.ClaimToken
	for _, kind := range []string{"renew", "release"} {
		business := map[string]any{"run_id": run, "action": kind}
		_, err := c.request(ctx, http.MethodPut, "/runs/"+run+"/claim", "late-old-"+kind, interopJSON(t, map[string]any{"business": business, "attempt": map[string]any{"claim_token": oldToken, "expected_run_revision": nil}}))
		if !errors.Is(err, ErrStaleClaim) {
			t.Fatalf("old %s changed new lease: %v", kind, err)
		}
	}
	action, err := PrepareLeaseAction("ack", claim, "takeover-ack", c.now())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = c.SendLeaseAction(ctx, action); err != nil {
		t.Fatal(err)
	}
	if _, err := interopControl(t, c, ctx, run, "manifest", "old-uncommitted-part", oldToken, part, nil); err == nil {
		t.Fatal("old uncommitted manifest accepted")
	}
	if _, err := interopControl(t, c, ctx, run, "result", "old-uncommitted-result", oldToken, result, frozen["revision"]); err == nil {
		t.Fatal("old uncommitted result accepted")
	}
	partReceipt, err := interopControl(t, c, ctx, run, "manifest", "takeover-part", token, part, nil)
	if err != nil {
		t.Fatal(err)
	}
	replay, err := interopControl(t, c, ctx, run, "manifest", "old-committed-part", oldToken, part, nil)
	if err != nil || replay["receipt_ref"] != partReceipt["receipt_ref"] || replay["current_lease_valid"] != false {
		t.Fatalf("committed manifest replay: %v", err)
	}
	revision := interopRead(t, c, ctx, "/runs/"+run)["run_revision"]
	if _, err := interopControl(t, c, ctx, run, "result", "stale-result-cas", token, result, float64(99999)); !errors.Is(err, ErrRunFrozen) {
		t.Fatalf("stale run CAS: %v", err)
	}
	receipt, err := interopControl(t, c, ctx, run, "result", "takeover-result", token, result, revision)
	if err != nil {
		t.Fatal(err)
	}
	replay, err = interopControl(t, c, ctx, run, "result", "old-committed-result", oldToken, result, frozen["revision"])
	if err != nil || replay["receipt_ref"] != receipt["receipt_ref"] || replay["current_lease_valid"] != false {
		t.Fatalf("committed result replay: %v", err)
	}
	// A late response cannot mutate a superseded local attempt/claim.
	hash := interopHash(t, ControlBusinessHash, map[string]any{"operation": "result", "business": result})
	queue := &memoryDeliveryQueue{controlCurrent: false, control: &ControlAttempt{ID: "old-result", BusinessID: "result", RunID: run, Kind: "run_result", IdempotencyKey: "old-committed-result", BusinessHash: hash, Wire: interopJSON(t, map[string]any{"business": result, "attempt": map[string]any{"claim_token": oldToken, "expected_run_revision": frozen["revision"]}})}}
	delivery, err := NewDelivery(c, queue)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := delivery.DeliverOneControl(ctx); !errors.Is(err, ErrSupersededAttempt) || queue.controlApplied {
		t.Fatalf("late result applied to new attempt: %v", err)
	}
}
