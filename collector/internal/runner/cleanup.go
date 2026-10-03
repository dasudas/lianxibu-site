package runner

import (
	"context"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
)

type terminalReader struct{ api *client.Client }

func (t terminalReader) ReadTerminal(ctx context.Context, id string) (outbox.TerminalSnapshot, error) {
	value, err := t.api.Terminal(ctx, id)
	if err != nil {
		return outbox.TerminalSnapshot{}, err
	}
	return outbox.TerminalSnapshot{RunID: id, DeliveryState: value.DeliveryState, ManifestHash: *value.ManifestHash,
		ResultBusinessHash: *value.ResultHash, PartCount: uint64(len(value.Parts)), ExpectedCount: *value.Expected,
		AcceptedCount: value.Accepted, ExcludedCount: value.Excluded, PendingCount: value.Pending, Parts: value.Parts}, nil
}

func (r *Runner) cleanup(ctx context.Context) error {
	claims, e := r.queue.ActiveClaims(ctx)
	if e != nil {
		return e
	}
	live := map[string]bool{}
	for _, claim := range claims {
		live[claim.Run.RunID] = true
	}
	ids, err := r.queue.CompletedRuns(ctx, r.cleanupCursor)
	if err != nil {
		return err
	}
	if len(ids) == 0 {
		r.cleanupCursor = ""
		return nil
	}
	for _, id := range ids {
		r.cleanupCursor = id
		if live[id] {
			continue
		}
		state, _, err := r.queue.Run(ctx, id)
		if err != nil {
			return err
		}
		if state.Retention == nil {
			err = r.queue.ConfirmTerminal(ctx, id, terminalReader{r.api})
			if errors.Is(err, outbox.ErrRetentionHold) || errors.Is(err, client.ErrRunFrozen) {
				continue
			}
			if err != nil {
				return err
			}
		}
		_, err = r.queue.CleanupConfirmedObservations(ctx, id, 100)
		if err != nil && !errors.Is(err, outbox.ErrRetentionHold) {
			return err
		}
	}
	return nil
}
