import test from 'node:test';
import assert from 'node:assert/strict';
import { createHomeApi } from '../src/api/home.js';
const report = { id: 'report/1', kind: 'report', title: '<script>Fictional</script>', revision: 1, body_revision: 1, publication_state: 'published', published_at: '2026-01-02T03:04:05.000000Z', review_required: false, blocking_reasons: [], affected_by_correction: false, coverage: { state: 'partial', missing_inputs: [], missing_dates: [], no_update_dates: [] } };
const pack = { id: 'package A', title: 'Fictional learning', description: 'Fictional', revision: 1, state: 'published' };
const fixture = () => ({ reports: [structuredClone(report)], learning_packages: [structuredClone(pack)], default_package_id: pack.id });
test('home projection uses HTTP, cancellation and complete safe links without carrying unknown/private fields', async () => {
  const data = fixture(); data.reports[0].internal_text = 'Fictional forbidden'; const signal = new AbortController().signal;
  const api = createHomeApi({ request: async (method, path, options) => { assert.equal(method, 'GET'); assert.equal(path, '/home'); assert.equal(options.signal, signal); return { data }; } });
  const result = await api.load({ signal }); assert.equal(result.reports[0].href, '/digests/report%2F1'); assert.equal(result.packages[0].href, '/roadmaps?package_id=package%20A');
  assert.equal(result.reports[0].title, report.title); assert.ok(Object.isFrozen(result.reports[0])); assert.equal(JSON.stringify(result).includes('forbidden'), false);
});
test('home refuses drafts, blocked/review-required content, identity collisions and unavailable default package', async () => {
  for (const mutate of [d => d.reports[0].publication_state = 'draft', d => d.reports[0].review_required = true, d => d.reports[0].blocking_reasons.push('blocked'), d => d.learning_packages[0].state = 'withdrawn', d => d.default_package_id = 'missing', d => d.reports.push(d.reports[0]), d => d.reports[0].published_at = '2026-02-31T03:04:05.000000Z', d => d.reports[0].revision = 9007199254740992, d => d.reports = Array(51).fill(report)]) {
    const data = fixture(); mutate(data); await assert.rejects(createHomeApi({ request: async () => ({ data }) }).load(), error => error.code === 'invalid_response');
  }
});
test('home keeps empty and absent default explicit and propagates service errors', async () => {
  const empty = await createHomeApi({ request: async () => ({ data: { reports: [], learning_packages: [], default_package_id: null } }) }).load(); assert.equal(empty.defaultPackageId, null); assert.deepEqual(empty.reports, []);
  const failure = new Error('Fictional network'); await assert.rejects(createHomeApi({ request: async () => { throw failure; } }).load(), e => e === failure);
});
