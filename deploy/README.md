# Independent build artifacts

The three CI jobs build backend, collector, and frontend separately. Each produces one immutable component directory containing its artifact and `manifest.json`; CI never deploys or enables a service.

`deploy/package.py` checks the artifact boundary, records the Git source commit and dependency-lock digests, and hashes the final artifact. The frontend archive fingerprints JS/CSS/static files and rewrites relative imports and HTML references. `index.html` is designated `no-cache`; fingerprinted assets are immutable. Keep prior asset versions until cached pages can no longer reference them.

The `.env.example` files are fictional field templates. Set live values outside Git through controlled runtime configuration. Current bootstrap manifests declare no required business features and unknown schema/queue compatibility bounds. Release must wait until the corresponding implementation, compatibility checks, and operational validation are complete.

To package locally after a component build, run `python deploy/package.py COMPONENT --input BUILT_PATH --output artifacts --version VERSION --source-commit FULL_GIT_SHA --build-id BUILD_ID --toolchain TOOLCHAIN_LABEL`. The output directory is ignored by Git, and an existing component/version is never overwritten.
