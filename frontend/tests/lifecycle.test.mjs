import test from 'node:test';
import assert from 'node:assert/strict';
import { createApiClient } from '../src/api/client.js';
import { createSessionState } from '../src/state/session.js';
import { createPageScope } from '../src/state/page-scope.js';
import { createPageRouter } from '../src/router/lifecycle.js';
import { createStatusView } from '../src/components/common/status-view.js';

function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
const identity = { authenticated: true, user_id: 'fictional-user', role: 'user', csrf_token: 'fictional-csrf' };
function setup() { const api = createApiClient({ fetchImpl: async () => ({ status: 200, ok: true, json: async () => ({ data: identity }) }) }); return { api, session: createSessionState(api) }; }

test('401/exit clears identity, private memory, listeners and graph resources before late completion', async () => {
  const { api, session } = setup(); await session.refresh();
  session.remember('note', 'Fictional private');
  const scope = createPageScope(session); const late = deferred(); let painted = 0, destroyed = 0;
  const target = new EventTarget(); scope.listen(target, 'change', () => painted++);
  scope.own(() => { throw new Error('broken disposer'); }); scope.own(() => destroyed++);
  const pending = scope.run('detail', () => late.promise, () => painted++);
  api.clearSession('unauthorized');
  assert.equal(session.snapshot.phase, 'unauthorized'); assert.equal(session.snapshot.user, null);
  assert.equal(session.recall('note'), undefined); assert.ok(scope.signal.aborted); assert.equal(destroyed, 1);
  target.dispatchEvent(new Event('change')); late.resolve('old private'); await pending;
  assert.equal(painted, 0); scope.dispose(); assert.equal(destroyed, 1); session.dispose();
});

test('overlapping loads only commit newest result and unmount rejects ignored aborts', async () => {
  const { session } = setup(); const scope = createPageScope(session); const a = deferred(), b = deferred(); const results = [];
  const first = scope.run('detail', () => a.promise, value => results.push(value));
  const second = scope.run('detail', () => b.promise, value => results.push(value));
  b.resolve('new'); await second; a.resolve('old'); await first; assert.deepEqual(results, ['new']);
  const c = deferred(); const third = scope.run('detail', () => c.promise, value => results.push(value)); scope.dispose(); c.resolve('unmounted'); await third;
  assert.deepEqual(results, ['new']); session.dispose();
});

test('late session refresh cannot restore exited identity or expose CSRF in view state', async () => {
  const late = deferred(); const api = createApiClient({ fetchImpl: () => late.promise }); const session = createSessionState(api);
  const refresh = session.refresh(); session.clear(); late.resolve({ status: 200, ok: true, json: async () => ({ data: identity }) }); await refresh;
  assert.equal(session.snapshot.user, null); assert.equal(session.snapshot.phase, 'unauthorized'); assert.equal(JSON.stringify(session.snapshot).includes('csrf'), false);
  session.dispose();
});

test('router disposes pages before navigation, remounts on session change and removes popstate listener', async () => {
  const { session } = setup(); const events = new EventTarget(); let href = 'https://example.invalid/'; const marks = [];
  const browser = { events, location: { get href() { return href; } }, history: { pushState(_s, _t, url) { href = String(url); }, replaceState(_s, _t, url) { href = String(url); } } };
  const router = createPageRouter({ browser, session, mount: ({ route, scope }) => { marks.push(route?.id ?? 'missing'); scope.own(() => marks.push('resources')); return () => marks.push('unmount'); } });
  router.start(); router.navigate('/login'); assert.deepEqual(marks.slice(0, 4), ['UI-01', 'unmount', 'resources', 'UI-02']);
  assert.throws(() => router.navigate('https://elsewhere.invalid/'), TypeError);
  session.clear(); assert.equal(marks.at(-1), 'UI-02'); router.dispose(); const length = marks.length;
  events.dispatchEvent(new Event('popstate')); session.clear(); assert.equal(marks.length, length); session.dispose();
});

class Element {
  constructor(tag, doc) { this.tagName = tag; this.ownerDocument = doc; this.children = []; this.attributes = {}; this.events = new EventTarget(); }
  append(...nodes) { this.children.push(...nodes); } replaceChildren(...nodes) { this.children = nodes; }
  setAttribute(k, v) { this.attributes[k] = v; } addEventListener(...args) { this.events.addEventListener(...args); } removeEventListener(...args) { this.events.removeEventListener(...args); }
  click() { this.events.dispatchEvent(new Event('click')); }
}
test('status component provides busy, empty, error recovery and unauthorized feedback without HTML injection', () => {
  const doc = { createElement: tag => new Element(tag, doc) }; const root = new Element('main', doc); let retried = 0;
  const view = createStatusView(root); view.show('loading'); assert.equal(root.attributes['aria-busy'], 'true');
  view.show('empty'); assert.equal(root.attributes['aria-busy'], 'false'); assert.match(root.children[0].textContent, /暂无/);
  view.show('error', { retry: () => retried++ }); const old = root.children[1]; old.click(); assert.equal(retried, 1);
  view.show('unauthorized'); old.click(); assert.equal(retried, 1); assert.match(root.children[0].textContent, /登录/);
  view.show('ready'); assert.equal(root.children.length, 0); view.dispose();
});
