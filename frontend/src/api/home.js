import { ApiError } from './client.js';

const invalid = () => { throw new ApiError(200, 'invalid_response', 'Homepage data is unavailable'); };
function text(value, max, empty = false) {
  if (typeof value !== 'string' || (!empty && !value) || [...value].length > max) invalid();
  return value;
}
function id(value) { text(value, 64); if (/[\u0000-\u001f\u007f]/.test(value)) invalid(); return value; }
function revision(value) { if (!Number.isSafeInteger(value) || value < 0) invalid(); return value; }
function list(value, max = 50) { if (!Array.isArray(value) || value.length > max) invalid(); return value; }
function timestamp(value) {
  if (value === null) return null;
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)
      || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString().slice(0, 23) !== value.slice(0, 23)) invalid();
  return value;
}
function unique(items) { if (new Set(items.map(item => item.id)).size !== items.length) invalid(); return Object.freeze(items); }

export function createHomeApi(client) {
  return Object.freeze({ async load({ signal } = {}) {
    const { data } = await client.request('GET', '/home', { signal });
    if (!data || typeof data !== 'object') invalid();
    const reports = unique(list(data.reports).map(item => {
      if (!item || item.kind !== 'report' || item.publication_state !== 'published' || item.review_required !== false
          || list(item.blocking_reasons, 100).length || typeof item.affected_by_correction !== 'boolean') invalid();
      if (!item.coverage || !['complete', 'partial', 'no_input'].includes(item.coverage.state)) invalid();
      list(item.coverage.missing_inputs, 1000); list(item.coverage.missing_dates, 366); list(item.coverage.no_update_dates, 366);
      const identifier = id(item.id);
      return Object.freeze({ id: identifier, title: text(item.title, 512), revision: revision(item.revision), bodyRevision: revision(item.body_revision),
        publishedAt: timestamp(item.published_at), coverage: item.coverage.state, corrected: item.affected_by_correction, href: `/digests/${encodeURIComponent(identifier)}` });
    }));
    const packages = unique(list(data.learning_packages).map(item => {
      if (!item || item.state !== 'published') invalid();
      const identifier = id(item.id);
      return Object.freeze({ id: identifier, title: text(item.title, 200), description: text(item.description, 16384, true), revision: revision(item.revision), href: `/roadmaps?package_id=${encodeURIComponent(identifier)}` });
    }));
    const defaultPackageId = data.default_package_id === null ? null : id(data.default_package_id);
    if (defaultPackageId !== null && !packages.some(item => item.id === defaultPackageId)) invalid();
    return Object.freeze({ reports, packages, defaultPackageId });
  } });
}
