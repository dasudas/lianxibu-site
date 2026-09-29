# API contracts

Contract package revision: 0.13. Wire protocol revision: 0.11.

The two OpenAPI 3.1 documents define the business and collector HTTP
surfaces. `schemas.json` is the shared JSON Schema 2020-12 definition set.
`operation-map.json` lists all 71 operations: 58 business and 13 collector.
`examples.json` and `wire-examples.json` contain fictional fixtures,
including intentionally invalid wire cases.

These files describe requests and responses. They do not provide
authentication, authorization, persistence, or source admission. Clients and
the service must still verify those rules at runtime.
