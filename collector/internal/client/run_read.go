package client

import (
	"context"
	"errors"
	"net/http"
	"net/url"
)

// RunRevision observes current server CAS state, without replacing frozen business identity.
func (c *Client) RunRevision(ctx context.Context, runID string) (uint64, error) {
	if _, err := wireID(runID, false); err != nil {
		return 0, err
	}
	raw, err := c.request(ctx, http.MethodGet, "/runs/"+url.PathEscape(runID), "", nil)
	if err != nil {
		return 0, err
	}
	var run struct {
		ID       string  `json:"run_id"`
		Revision *uint64 `json:"run_revision"`
	}
	if err = decodeData(raw, &run); err != nil {
		return 0, err
	}
	if run.ID != runID || run.Revision == nil || *run.Revision > maxSafeInteger {
		return 0, errors.New("invalid current run identity")
	}
	return *run.Revision, nil
}
