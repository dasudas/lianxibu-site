package outbox

import (
	"context"
	"database/sql"
	"errors"
	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"sync"
	"time"
)

type HostMemorySource interface {
	SampleHostMemory(context.Context) (client.HostMemorySnapshot, error)
}
type HostMemoryOutcome string

const (
	HostMemoryStopped HostMemoryOutcome = "stopped"
	HostMemoryBusy    HostMemoryOutcome = "busy"
	HostMemoryNotDue  HostMemoryOutcome = "not_due"
	HostMemorySent    HostMemoryOutcome = "sent"
	HostMemoryRetired HostMemoryOutcome = "retired"
)

type HostMemoryPublisher struct {
	store   *Store
	api     *client.Client
	source  HostMemorySource
	work    sync.Mutex
	life    sync.Mutex
	running bool
	ctx     context.Context
	cancel  context.CancelFunc
}

func NewHostMemoryPublisher(store *Store, api *client.Client, source HostMemorySource) (*HostMemoryPublisher, error) {
	if store == nil || api == nil || source == nil {
		return nil, ErrHostMemoryJournal
	}
	return &HostMemoryPublisher{store: store, api: api, source: source}, nil
}
func (p *HostMemoryPublisher) Start() {
	p.life.Lock()
	defer p.life.Unlock()
	if p.running {
		return
	}
	p.ctx, p.cancel = context.WithCancel(context.Background())
	p.running = true
}
func (p *HostMemoryPublisher) Stop() {
	p.life.Lock()
	defer p.life.Unlock()
	p.running = false
	if p.cancel != nil {
		p.cancel()
	}
}
func (p *HostMemoryPublisher) session() (context.Context, bool) {
	p.life.Lock()
	defer p.life.Unlock()
	return p.ctx, p.running
}
func (s *Store) HostMemorySnapshotDue(ctx context.Context) (bool, error) {
	due := false
	err := s.write(ctx, func(conn *sql.Conn) error {
		j, err := s.hostMemoryJournal(ctx, conn)
		if err != nil {
			return err
		}
		if j == nil || j.State == "pending" || j.State == "expired" {
			due = true
			return nil
		}
		snapshot, err := client.DecodeHostMemoryTelemetry(j.Snapshot)
		if err != nil {
			return ErrHostMemoryJournal
		}
		at, err := time.Parse(client.SourceContractTime, snapshot.ObservedAt)
		if err != nil {
			return ErrHostMemoryJournal
		}
		due = s.now().Sub(at) >= time.Minute
		return nil
	})
	return due, err
}

// Sampling and network happen outside every original SQLite transaction.
// Sent means an exact ACK was committed; it is not a healthy qualification.
func (p *HostMemoryPublisher) Tick(parent context.Context) (HostMemoryOutcome, error) {
	if parent == nil {
		return HostMemoryStopped, ErrHostMemoryJournal
	}
	if !p.work.TryLock() {
		return HostMemoryBusy, nil
	}
	defer p.work.Unlock()
	life, running := p.session()
	if !running {
		return HostMemoryStopped, nil
	}
	ctx, cancel := context.WithCancel(parent)
	detach := context.AfterFunc(life, cancel)
	defer func() { detach(); cancel() }()
	if ctx.Err() != nil || life.Err() != nil {
		return HostMemoryStopped, context.Canceled
	}
	queued, err := p.store.PendingHostMemorySnapshot(ctx)
	if err != nil {
		return "", err
	}
	if queued == nil {
		due, err := p.store.HostMemorySnapshotDue(ctx)
		if err != nil {
			return "", err
		}
		if !due {
			return HostMemoryNotDue, nil
		}
		snapshot, err := p.source.SampleHostMemory(ctx)
		if err != nil {
			return "", err
		}
		if ctx.Err() != nil || life.Err() != nil {
			return HostMemoryStopped, context.Canceled
		}
		var value QueuedHostMemorySnapshot
		err = p.persist(life, ctx, func() error { var e error; value, e = p.store.StageHostMemorySnapshot(ctx, snapshot); return e })
		if err != nil {
			return "", err
		}
		queued = &value
	}
	ack, err := p.api.SubmitHostMemorySnapshot(ctx, queued.Snapshot)
	if ctx.Err() != nil || life.Err() != nil {
		return HostMemoryStopped, context.Canceled
	}
	if err != nil {
		var rejected *client.HostMemoryRejected
		if errors.As(err, &rejected) {
			observed, e := time.Parse(client.SourceContractTime, queued.Snapshot.ObservedAt)
			if e == nil && p.store.now().Sub(observed) > 120*time.Second {
				if e = p.persist(life, ctx, func() error { return p.store.RetireRejectedHostMemorySnapshot(ctx, *queued, rejected) }); e != nil {
					return "", e
				}
				return HostMemoryRetired, nil
			}
		}
		return "", err
	}
	if err = p.persist(life, ctx, func() error { return p.store.ApplyHostMemoryAck(ctx, *queued, ack) }); err != nil {
		return "", err
	}
	return HostMemorySent, nil
}
func (*HostMemoryPublisher) String() string { return "HostMemoryPublisher[redacted]" }

func (p *HostMemoryPublisher) persist(session context.Context, ctx context.Context, work func() error) error {
	p.life.Lock()
	defer p.life.Unlock()
	if !p.running || p.ctx != session || session.Err() != nil || ctx.Err() != nil {
		return context.Canceled
	}
	return work()
}
