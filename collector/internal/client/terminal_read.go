package client

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
)

type TerminalState struct {
	RunID         string            `json:"run_id"`
	Revision      uint64            `json:"run_revision"`
	DeliveryState string            `json:"delivery_state"`
	ManifestHash  *string           `json:"manifest_hash"`
	ResultHash    *string           `json:"result_business_hash"`
	Expected      *uint64           `json:"expected_count"`
	Accepted      uint64            `json:"accepted_count"`
	Excluded      uint64            `json:"excluded_count"`
	Pending       uint64            `json:"pending_count"`
	Parts         []json.RawMessage `json:"-"`
}

// Terminal reads a stable run and every bounded manifest page; callers verify all hashes.
func (c *Client) Terminal(ctx context.Context, id string) (TerminalState, error) {
	if _, err := wireID(id, false); err != nil {
		return TerminalState{}, err
	}
	base := "/runs/" + url.PathEscape(id)
	read := func() (TerminalState, error) {
		var state TerminalState
		raw, err := c.request(ctx, http.MethodGet, base, "", nil)
		if err != nil {
			return state, err
		}
		err = decodeData(raw, &state)
		if err != nil {
			return state, err
		}
		if state.RunID != id || state.ManifestHash == nil || state.ResultHash == nil || state.Expected == nil {
			return state, ErrUntrustedAck
		}
		return state, nil
	}
	state, err := read()
	if err != nil {
		return state, err
	}
	switch state.DeliveryState {
	case "complete", "partial", "no_usable_input", "empty_success":
	default:
		return state, ErrRunFrozen
	}
	cursor := ""
	seen := map[string]bool{}
	size := 0
	for page := 0; page < 10000; page++ {
		path := base + "/manifest"
		if cursor != "" {
			path += "?cursor=" + url.QueryEscape(cursor)
		}
		raw, e := c.request(ctx, http.MethodGet, path, "", nil)
		if e != nil {
			return state, e
		}
		var data struct {
			RunID string            `json:"run_id"`
			Parts []json.RawMessage `json:"parts"`
			Next  *string           `json:"next_cursor"`
		}
		if e = decodeData(raw, &data); e != nil {
			return state, e
		}
		if data.RunID != id {
			return state, ErrUntrustedAck
		}
		size += len(raw)
		if size > 32<<20 || len(state.Parts)+len(data.Parts) > 10000 {
			return state, errors.New("terminal manifest exceeds read bound")
		}
		state.Parts = append(state.Parts, data.Parts...)
		if data.Next == nil {
			after, e := read()
			if e != nil {
				return state, e
			}
			if after.Revision != state.Revision || after.DeliveryState != state.DeliveryState || *after.ManifestHash != *state.ManifestHash || *after.ResultHash != *state.ResultHash || *after.Expected != *state.Expected || after.Accepted != state.Accepted || after.Excluded != state.Excluded || after.Pending != state.Pending {
				return state, ErrRevisionConflict
			}
			return state, nil
		}
		if *data.Next == "" || seen[*data.Next] {
			return state, ErrUntrustedAck
		}
		cursor = *data.Next
		seen[cursor] = true
	}
	return state, errors.New("terminal manifest page bound exceeded")
}
