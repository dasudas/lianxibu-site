# Collector host memory v1

This optional telemetry extension has its own versioned contract. Its presence
in this repository does not advertise an installed or enabled runtime.

## Request

`POST /api/collector/v1/host-memory`, `Content-Type: application/json`.
One current node Bearer with the existing `heartbeat:write` permission is
required. The node identity comes from that Bearer. Query parameters and an
`Idempotency-Key` header are forbidden. The maximum raw body is 8192 bytes.

The twelve fields in [the request schema](collector-host-memory-v1.schema.json)
are required and closed. All examples are
[fictional](collector-host-memory-v1-wire-examples.json).

`capture_epoch` identifies the actual continuous native sampler registration.
`capture_started_at` is the start of that observation stream; it is not an
estimated operating-system birth date. `process_identity` hashes the actual
boot identity, PID and process start ticks. `cgroup_epoch` hashes the actual
kernel resource identity. Hash inputs and filesystem paths are not transmitted.

RSS is the actual process resident byte count. `maximum_bytes: null` means
that native `memory.max` is unlimited. An absent field is unavailable and is
rejected; zero is not an unlimited budget. Consumer qualification also requires
the privately controlled host budget and cannot trust a client health flag.

`oom_events` and `oom_kills` are independent cumulative local kernel counters.
No ordering relationship between those two counts is implied. Measurements
are read from the collector's own cgroup, not a parent or descendant aggregate.
The semantics follow the [Linux cgroup v2 documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html).
The process start-tick identity follows
[proc_pid_stat](https://man7.org/linux/man-pages/man5/proc_pid_stat.5.html).

## Runtime requirements

UTC timestamps use exactly six fractional digits. Capture start must be at or
before observation. Numeric tokens must be nonnegative integers within the
safe canonical range; PID, sequence, RSS and a finite maximum must be positive.
The runtime rejects decimal numeric tokens such as `1.0`, even though JSON
Schema treats mathematically integral numbers as integers. Relational times,
raw-byte capacity and duplicate JSON keys are also checked by the runtime.

A new snapshot must not be future dated and must be at most 120 seconds old.
Within a capture lifetime, process identity, PID and start remain immutable,
sequence increases and observation time advances. A new capture needs a later
actual start. Damaged history and rollback cannot become healthy zero values.

## Response and replay

Success uses the existing envelope: `data` is the closed four-field ACK in the
schema's `$defs.ack`, and `meta.request_id` is a diagnostic request identity.
The ACK binds capture epoch, sequence, canonical ACTION payload hash and the
original server `received_at`. Clients verify those exact values before
discarding their frozen pending snapshot. Receipt acceptance grants no
source admission or permission to start business work.

Current authorization is checked for every replay and again in the sole
shared write transaction. An identical retained snapshot returns its original
ACK; replay never refreshes its timestamp. Same sequence with different bytes,
backward history or changed lifetime identity is refused. Node disable or
credential revocation also blocks an old ACK. Store history is bounded to the
latest three accepted snapshots; aged snapshots outside that retained history
cannot be resubmitted as new or refresh telemetry. This route does not change
the original heartbeat's last-seen or reconciliation evidence.

Responses use `Cache-Control: no-store`. Invalid authorization is 401, denied
current authority is 403, a boundary or duplicate-field error is 400, oversized
body is 413, invalid/stale evidence is 422, conflicting native history is 409,
and unavailable persistence is 503 with `Retry-After: 1`.

This extension retains the original seven-field heartbeat, frozen observation
wire revisions and SQLite v3 layout. Install both consumers before enabling
the separate host-memory flag. Production paths, credentials and real data
belong in controlled host configuration.
