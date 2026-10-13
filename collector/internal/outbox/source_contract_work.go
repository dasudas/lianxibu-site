package outbox

import (
	"context"
	"database/sql"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"time"
)

// BeginSourceContractWork is durable before any parser work or page persistence.
// A process crash leaves unknown facts, never a synthetic successful zero.
func (s *Store) BeginSourceContractWork(ctx context.Context, id string, version uint64) (string, error) {
	at := s.now().UTC().Truncate(time.Microsecond)
	var token string
	err := s.write(ctx, func(conn *sql.Conn) error {
		j, err := s.sourceJournal(ctx, conn, id, version, at)
		if err != nil || j.InFlight != "" {
			return ErrSourceContractJournal
		}
		token, err = randomID()
		if err != nil {
			return err
		}
		j.InFlight = token
		return s.saveSourceJournal(ctx, conn, j)
	})
	return token, err
}

// CompleteSourceContractWork captures format failure and clears its original token together.
// Network/cancel errors count zero format failures only when their completion commits.
func (s *Store) CompleteSourceContractWork(ctx context.Context, id string, version uint64, token string, formatFailure bool) error {
	at := s.now().UTC().Truncate(time.Microsecond)
	return s.write(ctx, func(conn *sql.Conn) error {
		j, err := s.sourceJournal(ctx, conn, id, version, at)
		if err != nil || token == "" || j.InFlight != token {
			return ErrSourceContractJournal
		}
		j.Events = pruneSourceEvents(j.Events, at)
		if formatFailure {
			if j.TotalFailures == maxSafe || len(j.Events) >= 200 {
				return ErrSourceContractJournal
			}
			j.TotalFailures++
			j.Events = append(j.Events, client.SourceFailureEvent{Sequence: j.TotalFailures, OccurredAt: at.Format(client.SourceContractTime)})
		}
		j.ObservedAt = at.Format(client.SourceContractTime)
		j.InFlight = ""
		return s.saveSourceJournal(ctx, conn, j)
	})
}
