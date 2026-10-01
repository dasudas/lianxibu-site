import test from 'node:test';
import assert from 'node:assert/strict';
import { createLearningReferenceApi } from '../src/api/learning-reference.js';
const task = kind => ({ packageId: 'package A', kind, id: 'object/1', title: 'Fictional frozen title', reason: 'Fictional reason' });
test('question/project references check the complete current object and keep frozen identity in links', async () => {
  for (const kind of ['question', 'project']) {
    const result = await createLearningReferenceApi({ request: async (_m, path) => { assert.equal(path, `/learning/packages/package%20A/${kind}s/object%2F1`); return { data: { package_id: 'package A', id: 'object/1' } }; } }).lookup(task(kind));
    assert.equal(result.state, 'available'); assert.equal(result.href, `/${kind}s/object%2F1?package_id=package%20A`);
  }
});
test('node reference pages real route membership instead of inventing a parent or switching package', async () => {
  const signal = new AbortController().signal, pages = [];
  const result = await createLearningReferenceApi({ request: async (_m, path, options) => {
    assert.equal(options.signal, signal);
    if (path.endsWith('/nodes/object%2F1')) return { data: { package_id: 'package A', id: 'object/1', module_id: 'fictional-module' } };
    assert.equal(path, '/learning/packages/package%20A/routes'); pages.push(options.query.page);
    return { data: { page: options.query.page, page_size: 50, total: 51, items: options.query.page === 1 ? Array.from({ length: 50 }, (_, i) => ({ package_id: 'package A', id: `fictional-${i}`, title: 'Other', module_ids: ['other-module'] })) : [{ package_id: 'package A', id: 'route/1', title: 'Actual route', module_ids: ['fictional-module'] }] } };
  } }).lookup(task('node'), { signal });
  assert.deepEqual(pages, [1, 2]); assert.equal(result.href, '/roadmaps/route%2F1/nodes/object%2F1?package_id=package%20A'); assert.equal(result.routeTitle, 'Actual route');
});
test('withdrawal, unknown transport and wrong identity never expose a usable link', async () => {
  for (const [status, state] of [[404, 'unavailable'], [410, 'unavailable'], [503, 'unknown']]) {
    const result = await createLearningReferenceApi({ request: async () => { throw { status }; } }).lookup(task('node')); assert.equal(result.state, state); assert.equal(result.href, null);
  }
  const wrong = await createLearningReferenceApi({ request: async () => ({ data: { package_id: 'another', id: 'object/1' } }) }).lookup(task('question')); assert.equal(wrong.state, 'unknown'); assert.equal(wrong.href, null);
  const absent = await createLearningReferenceApi({ request: async (_m, path) => ({ data: path.endsWith('/routes') ? { page: 1, page_size: 50, total: 0, items: [] } : { package_id: 'package A', id: 'object/1', module_id: 'fictional-module' } }) }).lookup(task('node')); assert.equal(absent.state, 'unavailable'); assert.equal(absent.href, null);
});
test('route lookup remains bounded and cancelled requests propagate to page disposal', async () => {
  let pages = 0;
  const result = await createLearningReferenceApi({ request: async (_m, path, options) => ({ data: path.endsWith('/routes') ? (pages++, { page: options.query.page, page_size: 50, total: 10000, items: [{ package_id: 'package A', id: `other-${pages}`, title: 'Other', module_ids: [] }] }) : { package_id: 'package A', id: 'object/1', module_id: 'fictional-module' } }) }).lookup(task('node'));
  assert.equal(pages, 100); assert.equal(result.state, 'unknown'); assert.equal(result.href, null);
  await assert.rejects(createLearningReferenceApi({ request: async () => { throw new DOMException('cancelled', 'AbortError'); } }).lookup(task('project')), e => e.name === 'AbortError');
});
