import test from 'node:test';
import assert from 'node:assert/strict';
import { mountReport, reportMarkup, taskMarkup } from '../src/pages/reports/detail.js';
import { createPageScope } from '../src/state/page-scope.js';
const report = { id: 'fictional-report', title: 'Fictional', bodyTitle: 'Fictional body', bodyRevision: 2, publicationState: 'published', publishedAt: null, type: 'daily', periodStart: '2026-01-01T16:00:00.000000Z', periodEnd: '2026-01-02T16:00:00.000000Z', inputSnapshotId: 'fictional-input', generation: 1, inputCutoffAt: null, coverage: { state: 'partial', missingInputs: [{ kind: 'source', id: 'fictional-source', businessDate: '2026-01-02', reason: 'Fictional unavailable' }], missingDates: ['2026-01-02'], noUpdateDates: ['2026-01-01'] }, reviewRequired: false, blockingReasons: [], corrected: true, sections: [{ heading: 'Fictional heading', text: '<script>fictional</script>\nFictional line', citationIds: ['fictional-citation'] }], citations: [{ id: 'fictional-citation', title: 'Fictional source', sourceUrl: 'https://example.invalid/story', revision: 1 }], learningTask: { packageId: 'fictional-package', kind: 'question', id: 'fictional-question', title: 'Fictional question', reason: 'Fictional reason' } };
test('report reader retains split columns and fixed coverage, escapes text and shows only verified learning links', () => {
  const markup = reportMarkup(report); assert.match(markup, /detail-layout split-detail/); assert.match(markup, /right-rail/); assert.match(markup, /正文 v2/); assert.match(markup, /发布时间未知/); assert.match(markup, /已核实无新增日期/); assert.match(markup, /缺失日期/);
  assert.doesNotMatch(markup, /<script>|25 min|今日小练习/); assert.match(markup, /&lt;script&gt;/); assert.match(markup, /rel="noopener noreferrer"/); assert.match(markup, /#report-section-1/);
  const available = taskMarkup(report.learningTask, { state: 'available', href: '/questions/fictional-question?package_id=fictional-package' }); assert.match(available, /package_id=fictional-package/);
  assert.doesNotMatch(taskMarkup(report.learningTask, { state: 'unavailable', href: null }), /href=/); assert.match(taskMarkup(report.learningTask, { state: 'unknown', href: null }), /data-learning-retry/);
});
class Element {
  constructor(doc) { this.ownerDocument = doc; this.events = new EventTarget(); this.children = []; this.attrs = {}; this.innerHTML = ''; this.classList = { add() {}, remove() {} }; }
  setAttribute(k, v) { this.attrs[k] = v; } append(...nodes) { this.children.push(...nodes); } replaceChildren(...nodes) { this.children = nodes; this.innerHTML = ''; }
  addEventListener(...args) { this.events.addEventListener(...args); } removeEventListener(...args) { this.events.removeEventListener(...args); }
}
function setup() {
  const session = { snapshot: { generation: 0, phase: 'ready', user: null }, subscribe: () => () => {}, refresh: async () => {} }, scope = createPageScope(session);
  const doc = { createElement: () => new Element(doc) }, root = new Element(doc), task = new Element(doc); root.querySelector = key => key === '#learning-reference' ? task : null; root.closest = () => new Element(doc);
  return { session, scope, root, task };
}
const tick = () => new Promise(r => setImmediate(r));
test('report and learning load independently, and standalone dispose rejects a late body', async () => {
  const ready = setup(); mountReport(ready.root, { scope: ready.scope, api: { detail: async () => report }, learning: { lookup: async () => ({ state: 'unavailable', href: null }) }, identifier: report.id, identity: ready.session.snapshot, session: ready.session }); await tick(); assert.match(ready.root.innerHTML, /Fictional body/); assert.match(ready.task.innerHTML, /已不可用/); ready.scope.dispose();
  const late = setup(); let resolve; const dispose = mountReport(late.root, { scope: late.scope, api: { detail: () => new Promise(r => { resolve = r; }) }, learning: {}, identifier: report.id, identity: late.session.snapshot, session: late.session }); dispose(); assert.equal(late.scope.signal.aborted, true); resolve(report); await tick(); assert.equal(late.root.innerHTML, '');
});
test('missing/withdrawn reports expose recoverable states and anonymous drafts never call the protected API', async () => {
  for (const [status, expected] of [[404, /未公开|找不到/], [410, /撤回/], [401, /登录/], [403, /无权/], [503, /重试/]]) {
    const f = setup(); mountReport(f.root, { scope: f.scope, api: { detail: async () => { throw { status }; } }, learning: {}, identifier: report.id, identity: f.session.snapshot, session: f.session }); await tick(); assert.match(f.root.children[0].textContent, expected); f.scope.dispose();
  }
  let calls = 0; const draft = setup(); mountReport(draft.root, { scope: draft.scope, api: { detail: async () => { calls++; } }, learning: {}, identifier: report.id, draft: true, identity: draft.session.snapshot, session: draft.session }); assert.equal(calls, 0); assert.match(draft.root.children[0].textContent, /登录/); draft.scope.dispose();
});
