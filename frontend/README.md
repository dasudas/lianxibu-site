# Frontend bootstrap

This package uses native JavaScript modules, HTML, and CSS. Node 24.19.0 and
npm 11.17.0 are pinned in `package.json`; no third-party build dependencies
are installed.

```sh
npm ci
npm test
npm run build
```

`dist/` is an independent static output. Its route table recognizes the 19
existing page identities. Page content and API integration are still pending;
the current pages show a temporary unavailable state. The build rejects demo
data modules, browser storage used as business state, and API calls from
components. Final asset fingerprinting belongs to the deployment build.
