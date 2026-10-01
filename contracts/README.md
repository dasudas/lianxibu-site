# API contracts

Contract package revision: 0.19. Observations accept wire revisions 0.11 and
0.12; configuration and control messages remain at 0.11.

The two OpenAPI 3.1 documents define the business and collector HTTP
surfaces. `schemas.json` is the shared JSON Schema 2020-12 definition set.
`operation-map.json` lists all 75 operations: 62 business and 13 collector.
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

Owner content metadata is available through GET /admin/content. Current
publication state is separate from public eligibility. Missing legacy body
versions and report types stay null; body payloads are excluded from the list.
Capabilities wire schema remains unchanged; package revision alone does not
advertise a runtime feature. Fictional metadata cases are in
admin-content-wire-examples.json.

version-disposition-wire-examples.json contains fictional positive and negative
cases for owner-only active invalidations of an exact content body version.
GET /admin/content/{kind}/{id}/version-dispositions requires revision and
body_hash; omitted input_job_id explicitly means null. It returns complete
version identity, independent validity revision and safe active invalidation
IDs/reasons/timestamps with real pagination. Operation receipts are not
invalidation IDs. Existing protocol definitions and wire capabilities stay
unchanged; this package revision does not advertise deployed support.

article-entity-wire-examples.json provides fictional opt-in entity summary cases.
Private article reads accept include_entity=true for an explicit nullable summary;
omitted/false preserves the original closed Article response. Only an exact,
unique news_tech article/source relationship is eligible. Unknown fields remain
null; counts and first/last times are never inferred from article metadata.
This package does not advertise deployed support; existing wire definitions
and runtime capability constants remain unchanged.

operation-affected-wire-examples.json covers owner-only frozen impact pages.
GET /admin/operations/{id}/affected reads an operation_ref from its receipt.
Pending enumeration has unknown total and no page; completed impact sets are
immutable and paged with operation-bound opaque cursors. Existing receipts
are unchanged. This package does not advertise deployed support.
