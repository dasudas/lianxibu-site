# Collector bootstrap

This is a Go 1.27.1 service skeleton. SQLite uses `modernc.org/sqlite`
v1.60.0 without CGO, so the collector can build for `linux/amd64` without
a C toolchain. The module has its own `go.mod` and `go.sum`.

```sh
go test ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o collector ./cmd/collector
```

The bootstrap requires `SITE_BASE_URL` (an HTTPS origin),
`COLLECTOR_NODE_ID`, `COLLECTOR_TOKEN`, and `COLLECTOR_SQLITE_PATH`
from host configuration. It does not fetch or upload anything yet, and it
does not read database or AI credentials. Use fictional values for local
checks; do not put operational values in this repository.
