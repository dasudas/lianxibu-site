# Independent build artifacts

The three CI jobs build backend, collector, and frontend separately. Each produces one immutable component directory containing its artifact and `manifest.json`; CI never deploys or enables a service.

`deploy/package.py` checks the artifact boundary, records the Git source commit and dependency-lock digests, and hashes the final artifact. The frontend archive fingerprints JS/CSS/static files and rewrites relative imports and HTML references. `index.html` is designated `no-cache`; fingerprinted assets are immutable. Keep prior asset versions until cached pages can no longer reference them.

The `.env.example` files are fictional field templates. Set live values outside Git through controlled runtime configuration. Current bootstrap manifests declare no required business features and unknown schema/queue compatibility bounds. Release must wait until the corresponding implementation, compatibility checks, and operational validation are complete.

To package locally after a component build, run `python deploy/package.py COMPONENT --input BUILT_PATH --output artifacts --version VERSION --source-commit FULL_GIT_SHA --build-id BUILD_ID --toolchain TOOLCHAIN_LABEL`. The output directory is ignored by Git, and an existing component/version is never overwritten.

## Read-only upgrade and rollback preflight

Run `python deploy/preflight.py --current BACKEND_MANIFEST COLLECTOR_MANIFEST FRONTEND_MANIFEST --candidate CANDIDATE_MANIFEST --facts CONTROLLED_FACTS_JSON --backend-env PRIVATE_BACKEND_ENV`.
The same check applies to upgrading or selecting an older artifact. It verifies artifact hashes, exact tested contract pairs, schema/queue bounds, and required backend capabilities. It never switches processes, extracts archives, changes a database, or writes a release record.

Controlled facts contain nonnegative integer `schema_revision` and `queue_schema_revision`, `backend_capabilities` with the selected backend's `artifact_sha256` and `supported_features`, and verified boolean `release_gates` for V03/V05/V07/V08/V09. Schema bounds in manifests are corresponding integer revisions; they must come from reviewed compatibility evidence, not be inferred from table counts. The frontend minimum is a dotted contract revision. Keep live facts outside Git.

Missing bounds, unknown capability evidence, or unfinished gates return exit 2. `compatibility_verified` only describes these input checks; `release_ready` additionally requires all supplied gates. A successful preflight does not independently authenticate evidence or replace an upgrade/rollback rehearsal. Current bootstrap manifests with unknown bounds intentionally fail this check.

The backend environment file must explicitly set all 17 formal business runtime switches to `true`: browser, collector, content, content availability and propagation, articles, sources and day roster, execution, execution preparation, catalog preparation, report preparation and runtime, ad-hoc preparation, worker, learning, and scheduler. The example keeps these disabled until deployment is ready. Missing, disabled, unreadable, duplicate, or malformed configuration prevents release even when artifact compatibility and supplied gates pass. All component upgrades and rollbacks apply this check to the selected backend's configuration.

Use a controlled UTF-8 file of at most 1 MiB containing one uppercase `KEY=value` assignment per line, blank lines, or whole-line `#`/`;` comments. Simple single or double quoted literal values are accepted; multiline values, escapes, `export`, and duplicate keys are rejected. This is a deliberately limited subset of the [systemd EnvironmentFile format](https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml). Values are never expanded or executed. The result records only a SHA-256 digest and fixed error codes or public switch names, with no values or private file path. `runtime_configuration_verified` describes the file read at check time; V07 must separately verify that the deployed process loads that same configuration. A later file edit requires another check.

## Public read measurements

`python deploy/load_read.py --base http://127.0.0.1:8080 --paths /home /reports /interpretations --users 200 --duration 30 --timeout 5 --think 0.1` measures a bounded, paced public GET workload. Run it only against an authorized target. It does not read cookies, environment proxy credentials, or response content into the report, and does not follow redirects. HTTPS is required outside loopback. Output contains request counts, HTTP status totals, peak in-flight requests, error rate, and millisecond histogram percentiles (rounded upward, capped at 120 seconds).

Without all of `--network-profile`, `--error-limit` (fraction), and `--p95-limit` (milliseconds), criteria remain unevaluated. These values must come from agreed acceptance conditions. Even a criteria match always has `capacity_qualified=false`: this tool alone does not cover browser rendering, writes, scheduler/AI/collector contention, resource budgets, or the target deployment topology. Do not equate configured users with simultaneous in-flight requests. Save any real environment measurements outside Git.

The public `formal-business-v1` capability is declared only when those 17 modes and their core providers compose together. Each new or restored browser application checks the frozen capability metadata (protocol `0.12`, metadata contract `0.15`) before business requests. This metadata version is distinct from the manifest business API contract version. Operational monitoring and real source admission remain separate release checks. Observing the artifact feature once does not replace checking the exact EnvironmentFile selected for an upgrade or rollback.

## Native operational configuration

Formal business configuration validation checks all 17 business switches. Operational qualification remains an independent release gate. Enable each operational switch only with its verified host files, identities and current rule configuration. The examples keep all operational and collector host-memory switches disabled.

Collector host-memory monitoring uses `SITE_OPS_COLLECTOR_MEMORY_ENABLED`, the existing `SITE_COLLECTOR_MONITOR_NODES` binding file and `SITE_COLLECTOR_MEMORY_BUDGET`. The budget file is a closed JSON object with `schema_version` equal to `collector-memory-budget-v1` and a positive safe integer `maximum_bytes`. The collector uses `COLLECTOR_HOST_MEMORY_ENABLED` and requires actual Linux proc and cgroup2 local memory counters. Match the managed service's hard limit to the independently controlled backend budget; an unlimited or mismatched native maximum cannot qualify recovery. Preserve the original SQLite v3 queue across restarts. Host-memory frames use a separate bounded journal in the existing node-config table.

Java memory, recovery, provider, source-contract submission, source monitoring, collector-node monitoring, API monitoring and backlog monitoring use their respective `SITE_OPS_*_ENABLED` switches. Host paths and opaque resource identities are private runtime inputs. RDS and cloud delivery additionally require their existing private credentials, scopes and notification channel. A current file digest does not prove that any running process loaded it; verify the deployed process and its native inputs separately.
