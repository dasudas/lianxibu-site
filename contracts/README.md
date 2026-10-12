# API contracts

Contract package revision: 0.34. Observations accept wire revisions 0.11 and
0.12; configuration and control messages remain at 0.11.

The two OpenAPI 3.1 documents define the business and collector HTTP
surfaces. `schemas.json` is the shared JSON Schema 2020-12 definition set.
`operation-map.json` lists all 81 operations: 68 business and 13 collector.
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

job-result-comparison-wire-examples.json contains fictional retained-content
comparison cases. Owner reads expose only validated current/candidate bodies
and their exact identities, independent content revisions, result hash and
frozen input metadata. Unavailable proof is explicit, never an invented body.
A read does not authorize applying a result; commands recheck both revisions,
result identity, generation and evidence in their transaction. Storage and
runtime providers must be connected separately; these contracts do not deploy them.

Owner frozen report inputs are read through
GET /admin/reports/{id}/input-summary with an explicit input_snapshot_id.
The closed projection uses persisted input membership, cutoff-time heat
counts, selected/excluded group totals and safe metadata. Daily inputs have
four groups; weekly/monthly inputs list frozen upstream report versions.
Unavailable proof stays unavailable; current source/entity rows cannot fill
old snapshots. Opaque pagination is bound to report, snapshot, projection
and limit. report-input-summary-wire-examples.json includes 29 fictional
schema and semantic cases; schema validation alone cannot prove cross-page
membership, arithmetic totals, period order or durable associations.

Anchored scalar patterns consume the entire wire string, including rejection
of an unwanted final newline. Existing valid fields, ranges, operations and
protocol revisions remain unchanged. scalar-tail-wire-examples.json contains
33 fictional scalar cases with inline schemas. Static schema validation does
not replace identity, association, duplicate, replay or result-hash checks.

Owner current answer reads use an explicit package/question composite identity.
The closed OwnerAnswerDetail includes drafts only behind owner authorization;
public Question reads are unchanged. Unknown version, text and visibility facts
remain null. Review and public visibility conclusions require one consistent
snapshot. owner-answer-wire-examples.json contains fictional boundary vectors.

## Owner current learning package

`GET /admin/learning/packages/{id}` returns a closed `OwnerPackageDetail` for one explicit package, including drafts. Unknown metadata remains null; revision zero and an empty description remain known values. Physical state is not proof of validation or public eligibility. No public read fallback, staged content, answers, raw JSON, hashes or private records. Existing import/actions and public schemas remain unchanged. [27 fictional schema vectors](owner-package-wire-examples.json). This contract is not a deployment claim.

## Owner current source

`GET /admin/sources/{id}` reads one explicit owner source using the existing closed `Source` schema. Draft/effective configuration and known zero revisions remain separate. It does not return raw legacy config, probe payloads or secret values. Missing sources return 404; unsupported legacy projection returns 503 without guessing identity or category. Stage is not probe or seven-day admission proof. A current GET does not prove an uncertain write receipt. Existing source requests and collector wire versions remain unchanged; the 158 definitions present in 0.25 are retained. [20 fictional schema vectors](owner-source-wire-examples.json). Not a deployment claim.

## Owner source action receipts

The existing `POST /admin/sources/{id}/actions` now returns `SourceActionResult`, keeping the existing eight-field envelope and adding an 80-character source root reference. All prior `ResourceRef` values remain schema-readable; `ActionResult`, `ResourceRef`, source requests and the other 79 operations are unchanged. Command validation remains mandatory: probe receipts identify a scheduled `source_run` and frozen generation, not successful admission; other receipts identify the exact source. Disabling an already disabled source is a version-preserving no-op. Current reads do not prove uncertain commands. Provider facades must freeze original receipts before release. [28 fictional schema and semantic vectors](owner-source-action-wire-examples.json). No new endpoint or collector wire revision.

## Job scheduling summary

`GET /admin/jobs/{id}/schedule` is an owner-only, no-store read of the exact job revision, authoritative frozen report interval, original queue admission time and snapshot observation time. Unknown proof remains null; job creation/update and next eligible time never substitute queue admission. Elapsed time means wall time since first enqueue, including retries. Existing Job and all prior operations are unchanged. [Fictional schema and semantic vectors](job-schedule-wire-examples.json). No scheduler, database migration or collector wire change.

## Source member command receipts

The owner source member action now returns `SourceItemActionResult`, with an exact source/run/item/original revision/action and complete `action-v1` command hash. An in-place member update and a new correction run have different root types; a correction preserves the frozen original. The previously unconsumed generic response for this operation is replaced; all other operations and ActionResult remain unchanged. [Fictional receipt vectors](source-item-action-wire-examples.json). No trial execution, schema migration or collector wire change.


Value result revision 2 is an additive model-only contract in
news-value-result-v2.schema.json. Its fictional schema cases are in
news-value-result-v2-wire-examples.json. It separates valuable from high_value;
high_value requires valuable. The frozen input must explicitly select version 2.
Existing inputs, requests, results and HTTP/collector schemas retain version 1.
Static schemas do not prove original membership, current-day eligibility,
unique entity selection or result hashes. Runtime consumers must be installed
before new preparations select this version.


External result verification also accepts value result revision 2 through
the existing owner resolve_external action. Its closed result branch is
additive; original ValueDecision/ValueResult definitions and operations are
unchanged. news-value-external-result-wire-examples.json contains 13 fictional
HTTP schema cases. Services must still verify the original input version,
result hash, attempt, evidence, permission, revision and call budget.


## 0.30 人工结果应用

`JobAction.apply_result.expected_content_revision` 字段仍必需，允许整数或 `null`。`null` 仅用于原始去重、价值判定或临时回答的无正文结果；分析与报告仍须提供实际当前正文版本。核实外部结果与应用结果是两次独立操作。原结果比较接口对无正文任务保持 `not_content_result`，不提供正文应用许可。


## 0.31 临时 AI 问题

`JobCreate` 的 `ad_hoc_ai` 分支必需 `request.prompt`（1～32768 Unicode 码点，语义非空），目标固定为 `ad_hoc_request`、`package_id: null`、`expected_revision: 0`。审计 `reason` 独立填写。相同问题不能通过更换编号或原因重置调用额度。新建回执表示原始意图 `waiting_input`，后续状态需读取该意图。其他任务保留原四字段；未知/重复字段拒绝。见 [虚构正负向样例](ad-hoc-job-wire-examples.json)。


## 0.32 站长临时问题及保留答案

`GET /admin/jobs/{id}/ad-hoc` 使用原临时问题的意图 ID，返回当前意图、精确原问题、可空原任务及可空保留答案。已有但损坏的证明返回 503；读取不发送 AI 或修改调用额度。所有信息仅站长可见，`no-store`。参见[虚构问答样例](ad-hoc-question-view-wire-examples.json)。


## 0.33 临时问题原意图取消

既有 `POST /admin/jobs/{id}/actions` 的 `cancel` 可处理尚未准备执行任务的原临时问题意图。已有原执行任务时，从原逻辑任务处理。取消保留原问题及调用身份，不重置额度；原幂等回执保持。参见[虚构取消样例](ad-hoc-intent-cancel-wire-examples.json)。


## 0.34 目录去重版本判断

`CatalogDedupResultV2` requires `dedup_result_version: "2"` and retains the
complete item set. It applies only to agent, skill, workflow and model dedup.
`version_changed` is a nullable boolean: null means unknown. Known comparisons
require both typed `catalog_version_evidence` proof references; unknown
comparisons keep both null. Reanalysis can be recommended only for an existing
candidate with a known version change. A known change may still recommend no
reanalysis. New or needs-review decisions keep the version unknown.

The original DedupDecision/DedupResult and news behavior remain at revision 1.
Services select revision 2 from the original frozen input and verify exact
items, candidates, version facts and both original native proof hashes. Schema
validation does not prove source admission, evidence ownership, result hashes
or permission to create a new generation. Unknown evidence is never a negative
reanalysis judgment. Install consumers before enabling new preparations.
See [standalone result schema](catalog-dedup-result-v2.schema.json) and
[fictional cases](catalog-dedup-result-v2-wire-examples.json).

## Optional collector host memory v1

The independently versioned host-memory extension has a
[closed schema](collector-host-memory-v1.schema.json),
[fictional wire cases](collector-host-memory-v1-wire-examples.json), and
[HTTP and replay requirements](collector-host-memory-v1.md).
It is separate from the original collector operation list and does not alter
the seven-field heartbeat, observation revisions, or SQLite v3 layout.
ACK acceptance does not imply healthy telemetry or business admission.
