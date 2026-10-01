# API contracts

Contract package revision: 0.15. Observations accept wire revisions 0.11 and
0.12; configuration and control messages remain at 0.11.

The two OpenAPI 3.1 documents define the business and collector HTTP
surfaces. `schemas.json` is the shared JSON Schema 2020-12 definition set.
`operation-map.json` lists all 72 operations: 59 business and 13 collector.
`examples.json`, `wire-examples.json`, and `hn-time-wire-examples.json`
contain fictional fixtures, including intentionally invalid wire cases.

An article at revision 0.12 requires nullable `submitted_at`. The submitted
time basis is limited to `global-hn`, with publication and update times null
and unknown source order. Revision 0.11 retains its original field set.
Frozen observation bytes and their hashes must never be rewritten.

These files describe requests and responses. They do not provide
authentication, authorization, persistence, or source admission. Clients and
the service must still verify those rules at runtime.

Operational rules may carry a bounded decimal-string parameter map. Changing
operational configuration requires a reason at the service boundary. Legacy
configuration without complete parameters remains readable but is not ready
to execute. Alert observed values may be null for unknown measurements; zero
is a known value. operational-wire-examples.json contains 18 fictional
positive and negative fixtures. Deploy consumers before enabling these rules.

article-record-wire-examples.json provides fictional aggregate record cases.
GET /private/article-records returns actor-owned current article records with
literal title, status and favorite filters, nullable article metadata and
independent full statistics. Existing record writes remain unchanged.
