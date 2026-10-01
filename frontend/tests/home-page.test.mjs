import test from 'node:test';
import assert from 'node:assert/strict';
import { homeMarkup, mountHome } from '../src/pages/home/index.js';
import { createPageScope } from '../src/state/page-scope.js';

const data = { reports: [{ id: 'fictional-report', title: '<img src=x onerror=alert(1)>', revision: 1, publishedAt: null, coverage: 'partial', corrected: true, href: '/digests/fictional-report' }], packages: [{ id: 'fictional-package', title: 'Fictional route', description: 'Fictional overview', href: '/roadmaps?package_id=fictional-package' }], defaultPackageId: 'fictional-package' };
test('homepage retains knowledge hero and report/right-rail layout using only service facts', () => {
  const markup = homeMarkup(data);
  assert.match(markup, /digest-hero knowledge-entry/); assert.match(markup, /home-grid/); assert.match(markup, /right-rail/); assert.match(markup, /knowledge-preview/);
  assert.match(markup, /href="\/roadmaps\?package_id=fictional-package"/); assert.match(markup, /发布时间未知/); assert.match(markup, /覆盖不完整/); assert.match(markup, /判定已更正/);
  assert.doesNotMatch(markup, /<img|草稿已就绪|25 min|给一个表单|前端开发 \/ Java/); assert.match(markup, /&lt;img/);
  const empty = homeMarkup({ reports: [], packages: [], defaultPackageId: null }); assert.match(empty, /还没有已发布的报告/); assert.match(empty, /暂无已发布的学习内容/);
});

class Element {
  constructor(doc) { this.ownerDocument = doc; this.children = []; this.attrs = {}; this.events = new EventTarget(); this.innerHTML = ''; }
  setAttribute(k, v) { this.attrs[k] = v; } append(...nodes) { this.children.push(...nodes); } replaceChildren(...nodes) { this.children = nodes; this.innerHTML = ''; }
  addEventListener(...args) { this.events.addEventListener(...args); } removeEventListener(...args) { this.events.removeEventListener(...args); }
}
function setup() {
  const session = { snapshot: { generation: 0 }, subscribe: () => () => {} }, scope = createPageScope(session);
  const doc = { createElement: () => new Element(doc) }; return { scope, root: new Element(doc) };
}
test('homepage loading and successful HTTP projection render, while late unmounted data never returns', async () => {
  const { scope, root } = setup(); let resolve;
  const dispose = mountHome(root, { scope, api: { load: ({ signal }) => { assert.ok(signal instanceof AbortSignal); return new Promise(r => { resolve = r; }); } } });
  assert.equal(root.attrs['aria-busy'], 'true'); dispose(); scope.dispose(); resolve(data); await new Promise(r => setImmediate(r)); assert.equal(root.innerHTML, '');
  const ready = setup(); mountHome(ready.root, { scope: ready.scope, api: { load: async () => data } }); await new Promise(r => setImmediate(r)); assert.match(ready.root.innerHTML, /Fictional route/); ready.scope.dispose();
});
test('homepage network failure offers same-page retry and 401 produces login feedback', async () => {
  const { scope, root } = setup(); let calls = 0;
  mountHome(root, { scope, api: { load: async () => { if (++calls === 1) throw new Error('Fictional network'); return data; } } });
  await new Promise(r => setImmediate(r)); assert.match(root.children[0].textContent, /重试/); root.children[1].events.dispatchEvent(new Event('click'));
  await new Promise(r => setImmediate(r)); assert.match(root.innerHTML, /Fictional route/); scope.dispose();
  const denied = setup(); mountHome(denied.root, { scope: denied.scope, api: { load: async () => { throw { status: 401 }; } } }); await new Promise(r => setImmediate(r)); assert.match(denied.root.children[0].textContent, /登录/); denied.scope.dispose();
});
