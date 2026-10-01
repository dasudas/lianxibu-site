import { ApiError } from './client.js';

const invalid = () => { throw new ApiError(200, 'invalid_response', 'Report data is unavailable'); };
const freeze = value => {
  if (value && typeof value === 'object') { for (const child of Object.values(value)) freeze(child); Object.freeze(value); }
  return value;
};
function text(value, max, empty = false) { if (typeof value !== 'string' || (!empty && !value) || [...value].length > max) invalid(); return value; }
function id(value) { text(value, 64); if (/[\u0000-\u001f\u007f]/.test(value)) invalid(); return value; }
function integer(value) { if (!Number.isSafeInteger(value) || value < 0) invalid(); return value; }
function list(value, max) { if (!Array.isArray(value) || value.length > max) invalid(); return value; }
function boolean(value) { if (typeof value !== 'boolean') invalid(); return value; }
function nullable(value, parse) { return value === null ? null : parse(value); }
function instant(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)
      || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString().slice(0, 23) !== value.slice(0, 23)) invalid();
  return value;
}
function date(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString().slice(0, 10) !== value) invalid();
  return value;
}
function coverage(value) {
  if (!value || !['complete', 'partial', 'no_input'].includes(value.state)) invalid();
  return { state: value.state,
    missingInputs: list(value.missing_inputs, 1000).map(item => { if (!item) invalid(); return { kind: text(item.kind, 32), id: nullable(item.id, id), businessDate: nullable(item.business_date, date), reason: text(item.reason, 256) }; }),
    missingDates: list(value.missing_dates, 366).map(date), noUpdateDates: list(value.no_update_dates, 366).map(date) };
}
export function reportSummary(value, { draft = false } = {}) {
  if (!value || value.kind !== 'report' || !['draft', 'approved', 'published', 'withdrawn'].includes(value.publication_state)) invalid();
  const blockingReasons = list(value.blocking_reasons, 100).map(item => text(item, 256));
  const reviewRequired = boolean(value.review_required);
  if (!draft && (value.publication_state !== 'published' || reviewRequired || blockingReasons.length)) invalid();
  return freeze({ id: id(value.id), title: text(value.title, 512), revision: integer(value.revision), bodyRevision: integer(value.body_revision),
    publicationState: value.publication_state, publishedAt: nullable(value.published_at, instant), inputSnapshotId: nullable(value.input_snapshot_id, id),
    generation: nullable(value.generation, integer), inputCutoffAt: nullable(value.input_cutoff_at, instant), coverage: coverage(value.coverage),
    reviewRequired, blockingReasons, corrected: boolean(value.affected_by_correction), supersedesJobId: nullable(value.supersedes_job_id, id) });
}
function sourceUrl(value) {
  if (value === null) return null;
  text(value, 2000); if (!value.startsWith('https://') || /[\u0000-\u0020\u007f\\]/.test(value)) invalid();
  let url; try { url = new URL(value); } catch { invalid(); }
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password) invalid(); return value;
}
function citation(value) {
  if (!value || !['article_revision', 'catalog_evidence', 'content_version'].includes(value.kind)) invalid();
  const bodyHash = nullable(value.body_hash, hash => { if (typeof hash !== 'string' || !/^[0-9a-f]{64}$/.test(hash)) invalid(); return hash; });
  return { id: id(value.id), kind: value.kind, title: text(value.title, 512), sourceUrl: sourceUrl(value.source_url), revision: nullable(value.revision, integer), bodyHash };
}
function task(value) {
  if (value === null) return null;
  if (!value || !['node', 'question', 'project'].includes(value.object_kind)) invalid();
  return { packageId: id(value.package_id), kind: value.object_kind, id: id(value.object_id), title: text(value.title, 512), reason: text(value.reason, 2048) };
}
export function createReportsApi(client) {
  return Object.freeze({ async list({ type, page = 1, page_size = 20, signal } = {}) {
    if (!['daily', 'weekly', 'monthly'].includes(type) || !Number.isSafeInteger(page) || page < 1
        || !Number.isSafeInteger(page_size) || page_size < 1 || page_size > 50) throw new TypeError('Invalid report page');
    const { data } = await client.request('GET', '/reports', { signal, query: { type, page, page_size } });
    if (!data || data.page !== page || data.page_size !== page_size) invalid();
    const total = integer(data.total), seen = new Set();
    const prefix = { daily: 'digests', weekly: 'weekly-reports', monthly: 'monthly-reports' }[type];
    const items = list(data.items, page_size).map(value => {
      const summary = reportSummary(value); if (seen.has(summary.id)) invalid(); seen.add(summary.id);
      return { ...summary, href: `/${prefix}/${encodeURIComponent(summary.id)}` };
    });
    if (items.length > Math.max(0, total - (page - 1) * page_size)) invalid();
    return freeze({ items, page, pageSize: page_size, total, type });
  }, async detail(identifier, { signal, draft = false } = {}) {
    id(identifier); if (typeof draft !== 'boolean') throw new TypeError('Draft mode must be explicit');
    const { data } = await client.request('GET', `${draft ? '/admin/content/report' : '/reports'}/${encodeURIComponent(identifier)}`, { signal });
    const summary = reportSummary(data, { draft }); if (summary.id !== identifier) invalid();
    const body = data.body;
    if (!body || !['daily', 'weekly', 'monthly'].includes(body.type) || body.timezone !== 'Asia/Shanghai') invalid();
    const periodStart = instant(body.period_start), periodEnd = instant(body.period_end); if (periodStart >= periodEnd) invalid();
    const learningTask = task(body.learning_task); if (learningTask && body.type !== 'daily') invalid();
    const citations = list(data.citations, 1000).map(citation), known = new Set(citations.map(item => item.id));
    if (known.size !== citations.length) invalid();
    const references = values => list(values, 1000).map(value => { const key = id(value); if (!known.has(key)) invalid(); return key; });
    const citationIds = references(body.citation_ids);
    const sections = list(body.sections, 100).map(section => {
      if (!section) invalid(); const sectionRefs = references(section.citation_ids);
      if (sectionRefs.some(key => !citationIds.includes(key))) invalid();
      return { heading: text(section.heading, 512), text: text(section.text, 200000, true), citationIds: sectionRefs };
    });
    return freeze({ ...summary, bodyTitle: text(body.title, 512), type: body.type, periodStart, periodEnd, timezone: body.timezone, sections, citationIds, citations, learningTask });
  } });
}
