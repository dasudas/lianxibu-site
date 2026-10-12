// Package runner composes durable collection and delivery; it never promotes a source.
package runner

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"time"

	"github.com/dasudas/lianxibu-site/collector/internal/client"
	"github.com/dasudas/lianxibu-site/collector/internal/outbox"
	"github.com/dasudas/lianxibu-site/collector/internal/parsers"
	"github.com/dasudas/lianxibu-site/collector/internal/scheduler"
	"github.com/dasudas/lianxibu-site/collector/internal/telemetry"
)

type Monitor interface {
	Tick(context.Context) (telemetry.State, error)
	AllowNewInput() error
}
type sourceCompletion struct {
	id            string
	version       uint64
	token         string
	formatFailure bool
}

type Runner struct {
	pendingSourceCompletion *sourceCompletion
	api                     *client.Client
	queue                   *outbox.Store
	delivery                *client.Delivery
	fetch                   scheduler.Fetcher
	monitor                 Monitor
	now                     func() time.Time
	cleanupCursor           string
	licenseReviews          parsers.LicenseReviews
}

func New(api *client.Client, queue *outbox.Store, fetch scheduler.Fetcher, monitor Monitor, now func() time.Time) (*Runner, error) {
	if api == nil || queue == nil || fetch == nil || monitor == nil || now == nil {
		return nil, errors.New("collector runtime dependencies required")
	}
	delivery, err := client.NewDelivery(api, queue)
	if err != nil {
		return nil, err
	}
	return &Runner{api: api, queue: queue, delivery: delivery, fetch: fetch, monitor: monitor, now: now}, nil
}

// SetLicenseReviews must be called during startup, before Run or Tick.
func (r *Runner) SetLicenseReviews(reviews parsers.LicenseReviews) { r.licenseReviews = reviews }

func (r *Runner) reviewedAdapter(version string) (scheduler.Adapter, error) {
	a, err := adapter(version)
	if catalog, ok := a.(*parsers.RepositoryCatalogAdapter); ok {
		catalog.SetLicenseReviews(r.licenseReviews)
	}
	return a, err
}

// Run uses a bounded delay between cycles. Errors are classified by the caller,
// which must not log remote URLs, response bodies, tokens or source payloads.
func (r *Runner) Run(ctx context.Context, report func(error)) error {
	if err := r.queue.Recover(ctx); err != nil {
		return err
	}
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := r.Tick(ctx); err != nil && report != nil {
			report(err)
		}
		timer := time.NewTimer(5 * time.Second)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}

func (r *Runner) drain(ctx context.Context) error {
	for i := 0; i < 20; i++ {
		sent, err := r.delivery.DeliverOneControl(ctx)
		if err != nil {
			return err
		}
		if !sent {
			break
		}
	}
	for i := 0; i < 10; i++ {
		n, err := r.delivery.UploadOneBatch(ctx)
		if err != nil {
			return err
		}
		if n == 0 {
			break
		}
	}
	return nil
}

func (r *Runner) Tick(ctx context.Context) error {
	_, sampleErr := r.monitor.Tick(ctx)
	// Old frozen bytes can still be delivered while disk pressure pauses new input.
	if err := r.drain(ctx); err != nil {
		return err
	}
	if err := r.cleanup(ctx); err != nil {
		return err
	}
	if err := r.flushSourceCompletion(ctx); err != nil {
		return err
	}
	if sampleErr != nil {
		return sampleErr
	}
	if err := r.monitor.AllowNewInput(); err != nil {
		return err
	}
	config, err := r.api.Config(ctx)
	if err != nil {
		return err
	}
	active, err := r.queue.ActiveClaims(ctx)
	if err != nil {
		return err
	}
	allowed := map[string]client.SourceConfig{}
	ids := []string{}
	for _, s := range config.Sources {
		if s.ActivationStage == "production" && s.Config.AuthSecretRef == nil {
			if _, e := adapter(s.Config.ParserVersion); e == nil {
				allowed[s.SourceID] = s
				ids = append(ids, s.SourceID)
				if err := r.queue.PublishSourceContractSnapshot(ctx, r.api, s.SourceID, s.ConfigVersion); err != nil {
					return err
				}
			}
		}
	}
	if len(active) == 0 && len(ids) > 0 {
		var key [16]byte
		if _, err = rand.Read(key[:]); err != nil {
			return err
		}
		if _, err = r.api.Claim(ctx, hex.EncodeToString(key[:]), ids, r.queue); err != nil {
			return err
		}
		if err = r.drain(ctx); err != nil {
			return err
		}
		active, err = r.queue.ActiveClaims(ctx)
		if err != nil {
			return err
		}
	}
	var sourceFailures []error
	for _, c := range active {
		if err := ctx.Err(); err != nil {
			return err
		}
		done, err := r.queue.ResultAcknowledged(ctx, c.Run.RunID)
		if err != nil {
			return err
		}
		if done {
			if _, err = r.queue.StageLeaseAction(ctx, c.Run.RunID, *c.ClaimToken, "release"); err != nil {
				return err
			}
			continue
		}
		until, err := time.Parse("2006-01-02T15:04:05.000000Z", *c.LeaseUntil)
		if err != nil {
			return err
		}
		if until.Sub(r.now()) < time.Minute {
			if _, err = r.queue.StageLeaseAction(ctx, c.Run.RunID, *c.ClaimToken, "renew"); err != nil {
				return err
			}
			continue
		}
		state, _, err := r.queue.Run(ctx, c.Run.RunID)
		if err != nil {
			return err
		}
		if !state.FetchComplete {
			current, ok := allowed[c.Run.SourceID]
			validUntil, e := time.Parse("2006-01-02T15:04:05.000000Z", c.Config.Config.ValidUntil)
			if !ok || current.ConfigVersion != c.Run.ConfigVersion || e != nil || !r.now().Before(validUntil) {
				continue
			}
			if err = r.monitor.AllowNewInput(); err != nil {
				return err
			}
			a, err := r.reviewedAdapter(c.Config.Config.ParserVersion)
			if err != nil {
				return err
			}
			work, err := scheduler.New(r.fetch, r.queue, a, r.now, 20, 30*time.Second)
			if err != nil {
				return err
			}
			workToken, e := r.queue.BeginSourceContractWork(ctx, c.Run.SourceID, c.Run.ConfigVersion)
			if e != nil {
				return e
			}
			runCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
			outcome, err := work.Run(runCtx, c)
			cancel()
			r.pendingSourceCompletion = &sourceCompletion{id: c.Run.SourceID, version: c.Run.ConfigVersion, token: workToken, formatFailure: errors.Is(err, scheduler.ErrUnusablePage)}
			if captureErr := r.flushSourceCompletion(ctx); captureErr != nil {
				return errors.Join(err, captureErr)
			}
			if err != nil {
				// A failing source retains its checkpoint; independent claims still progress.
				sourceFailures = append(sourceFailures, err)
				continue
			}
			if !outcome.Finished {
				continue
			}
		}
		revision, e := r.api.RunRevision(ctx, c.Run.RunID)
		if e != nil {
			return e
		}
		if err = r.queue.StageCompletedRun(ctx, c, revision); err != nil {
			return err
		}
	}
	return errors.Join(append(sourceFailures, r.drain(ctx))...)
}

func adapter(version string) (scheduler.Adapter, error) {
	switch version {
	case "cn-ruanyifeng-v1":
		return parsers.Ruanyifeng(), nil
	case "cn-solidot-v1":
		return parsers.Solidot(), nil
	case "cn-cnblogs-v1":
		return parsers.Cnblogs(), nil
	case "cn-ithome-v1":
		return parsers.Ithome(), nil
	case "global-simon-v1":
		return parsers.Simon(), nil
	case "global-github-blog-v1":
		return parsers.GitHubBlog(), nil
	case "global-cloudflare-v1":
		return parsers.Cloudflare(), nil
	case "global-react-v1":
		return parsers.React(), nil
	case "global-vue-v1":
		return parsers.Vue(), nil
	case "global-spring-v1":
		return parsers.Spring(), nil
	case "global-ollama-v1":
		return parsers.Ollama(), nil
	case "global-arxiv-v1":
		return parsers.Arxiv(), nil
	case "global-hn-v1":
		return parsers.HackerNews(), nil
	case "github-agent-v1":
		return parsers.Agents(), nil
	case "github-workflow-v1":
		return parsers.Workflows(), nil
	case "github-skill-v1", "github-skill-v2":
		return parsers.Skills(), nil
	case "skillsbench-v1":
		return parsers.SkillsBench(), nil
	case "livebench-v1":
		return parsers.LiveBench(), nil
	case "hf-hle-openevals-v1":
		return parsers.HLE(), nil
	default:
		return nil, errors.New("unsupported configured parser")
	}
}

// AuditAdapter uses the identical parser factory without scheduling production work.
func AuditAdapter(version string, reviews parsers.LicenseReviews) (scheduler.Adapter, error) {
	a, err := adapter(version)
	if catalog, ok := a.(*parsers.RepositoryCatalogAdapter); ok {
		catalog.SetLicenseReviews(reviews)
	}
	return a, err
}

// Retry an unknown local completion before any later source claim or fetch.
func (r *Runner) flushSourceCompletion(ctx context.Context) error {
	pending := r.pendingSourceCompletion
	if pending == nil {
		return nil
	}
	if err := r.queue.CompleteSourceContractWork(ctx, pending.id, pending.version, pending.token, pending.formatFailure); err != nil {
		return err
	}
	r.pendingSourceCompletion = nil
	return nil
}
