# Public repository information boundary

`lianxibu-site` is open source. Never commit, push, publish, or paste real secrets or data that reveals private infrastructure or people. This applies to source, tests, fixtures, documentation, Git history, pull requests, issues, CI logs, generated artifacts, and frontend bundles.

Forbidden examples include passwords, tokens, private keys, cookies, database connection strings, authenticated URLs, production host/IP/port/account combinations, SSH configuration, environment files, server or console screenshots, database exports, SQLite outboxes, real logs and traces, AI request payloads, private user content, and internal design or audit evidence. Use unmistakably fictional placeholders in public examples. Never copy private `lianxibu-site-design` documents or attachments into this repository.

Inject operational values at runtime through a controlled secret store or host configuration. Keep only templates without real values in Git. Browser assets and source maps may contain only public configuration and public data.

Before every commit and push, inspect staged changes and newly added files for secrets and private information. Before release, inspect CI output and built artifacts as well. `.gitignore` is a convenience, not a security boundary: tracked files and forced additions still need inspection. If information reaches Git or a public surface, stop release, remove exposure from public history where possible, and rotate any affected credential. Never paste the exposed value into a remediation ticket.

The repository CI runs a full-history Gitleaks scan on pushes and pull requests. It is a second check; it does not replace manual review for server addresses, screenshots, data exports, and other exposure that may not match a secret pattern. Keep this scan green before merge or release. If the repository belongs to a GitHub organization, configure the required `GITLEAKS_LICENSE` repository secret; otherwise the scan must fail closed rather than be disabled.
