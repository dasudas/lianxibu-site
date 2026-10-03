# Independent build artifacts

The three CI jobs build backend, collector, and frontend separately. Each produces one immutable component directory containing its artifact and `manifest.json`; CI never deploys or enables a service.

`deploy/package.py` checks the artifact boundary, records the Git source commit and dependency-lock digests, and hashes the final artifact. The frontend archive fingerprints JS/CSS/static files and rewrites relative imports and HTML references. `index.html` is designated `no-cache`; fingerprinted assets are immutable. Keep prior asset versions until cached pages can no longer reference them.

The `.env.example` files are fictional field templates. Set live values outside Git through controlled runtime configuration. Current bootstrap manifests declare no required business features and unknown schema/queue compatibility bounds. Release must wait until the corresponding implementation, compatibility checks, and operational validation are complete.

To package locally after a component build, run `python deploy/package.py COMPONENT --input BUILT_PATH --output artifacts --version VERSION --source-commit FULL_GIT_SHA --build-id BUILD_ID --toolchain TOOLCHAIN_LABEL`. The output directory is ignored by Git, and an existing component/version is never overwritten.

## Read-only upgrade and rollback preflight

Run `python deploy/preflight.py --current BACKEND_MANIFEST COLLECTOR_MANIFEST FRONTEND_MANIFEST --candidate CANDIDATE_MANIFEST --facts CONTROLLED_FACTS_JSON`.
The same check applies to upgrading or selecting an older artifact. It verifies artifact hashes, exact tested contract pairs, schema/queue bounds, and required backend capabilities. It never switches processes, extracts archives, changes a database, or writes a release record.

Controlled facts contain nonnegative integer `schema_revision` and `queue_schema_revision`, `backend_capabilities` with the selected backend's `artifact_sha256` and `supported_features`, and verified boolean `release_gates` for V03/V05/V07/V08/V09. Schema bounds in manifests are corresponding integer revisions; they must come from reviewed compatibility evidence, not be inferred from table counts. The frontend minimum is a dotted contract revision. Keep live facts outside Git.

Missing bounds, unknown capability evidence, or unfinished gates return exit 2. `compatibility_verified` only describes these input checks; `release_ready` additionally requires all supplied gates. A successful preflight does not independently authenticate evidence or replace an upgrade/rollback rehearsal. Current bootstrap manifests with unknown bounds intentionally fail this check.
