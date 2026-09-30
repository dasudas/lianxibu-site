package client

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
)

// ReconcileControl confirms a lost manifest/result response from readback.
// A missing or different remote business remains pending for a new claim.
func (d *Delivery) ReconcileControl(ctx context.Context, attempt ControlAttempt) (bool, error) {
	if attempt.BusinessID == "" || attempt.RunID == "" || attempt.BusinessHash == "" ||
		attempt.RunID != url.PathEscape(attempt.RunID) {
		return false, ErrUntrustedAck
	}
	base := "/runs/" + url.PathEscape(attempt.RunID)
	confirmed := false
	switch attempt.Kind {
	case "run_result":
		raw, err := d.client.request(ctx, http.MethodGet, base, "", nil)
		if err != nil {
			return false, err
		}
		var run struct {
			RunID              string  `json:"run_id"`
			ResultBusinessHash *string `json:"result_business_hash"`
		}
		if err := decodeData(raw, &run); err != nil || run.RunID != attempt.RunID {
			return false, ErrUntrustedAck
		}
		confirmed = run.ResultBusinessHash != nil && *run.ResultBusinessHash == attempt.BusinessHash
	case "manifest":
		value, err := ParseStrict(attempt.Wire)
		if err != nil {
			return false, err
		}
		outer, err := object(value, "business", "attempt")
		if err != nil {
			return false, err
		}
		business, ok := outer["business"].(map[string]any)
		if !ok || business["run_id"] != attempt.RunID {
			return false, ErrUntrustedAck
		}
		cursor := ""
		seen := map[string]bool{}
		for page := 0; page < 64; page++ {
			path := base + "/manifest"
			if cursor != "" {
				path += "?cursor=" + url.QueryEscape(cursor)
			}
			raw, err := d.client.request(ctx, http.MethodGet, path, "", nil)
			if err != nil {
				return false, err
			}
			value, err := ParseStrict(raw)
			if err != nil {
				return false, err
			}
			envelope, ok := value.(map[string]any)
			if !ok {
				return false, ErrUntrustedAck
			}
			data, ok := envelope["data"].(map[string]any)
			if !ok || data["run_id"] != attempt.RunID {
				return false, ErrUntrustedAck
			}
			parts, ok := data["parts"].([]any)
			if !ok {
				return false, ErrUntrustedAck
			}
			for _, item := range parts {
				part, ok := item.(map[string]any)
				if !ok || part["run_id"] != attempt.RunID {
					return false, ErrUntrustedAck
				}
				if part["part_no"] != business["part_no"] {
					continue
				}
				hash, err := Hash(ControlBusinessHash, map[string]any{"operation": "manifest", "business": part})
				if err != nil || hash != attempt.BusinessHash {
					return false, ErrUntrustedAck
				}
				confirmed = true
				break
			}
			if confirmed || data["next_cursor"] == nil {
				break
			}
			next, ok := data["next_cursor"].(string)
			if !ok || next == "" || seen[next] {
				return false, ErrUntrustedAck
			}
			seen[next] = true
			cursor = next
			if page == 63 {
				return false, errors.New("manifest readback page bound exceeded")
			}
		}
	default:
		return false, fmt.Errorf("control kind %s cannot be reconciled by run readback", attempt.Kind)
	}
	if !confirmed {
		return false, nil
	}
	return d.queue.ReconcileBusiness(ctx, attempt.BusinessID, attempt.BusinessHash, "readback:"+attempt.BusinessHash)
}
