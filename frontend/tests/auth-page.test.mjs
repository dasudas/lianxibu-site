import test from 'node:test';
import assert from 'node:assert/strict';
import { mountLogin, loginMarkup } from '../src/pages/auth/index.js';
import { safeReturnPath } from '../src/router/return-path.js';
import { createPageScope } from '../src/state/page-scope.js';

test('login retains existing form layout without demo credentials and accepts only same-site known return paths', () => {
  assert.match(loginMarkup, /login-wrap/); assert.match(loginMarkup, /login-intro/); assert.match(loginMarkup, /login-card/); assert.match(loginMarkup, /autocomplete="current-password"/);
  assert.doesNotMatch(loginMarkup, /demo|演示|lianxibu123|注册按钮/);
  for (const path of ['https://elsewhere.invalid/', '//elsewhere.invalid/', '/\\elsewhere.invalid/', 'javascript:alert(1)', '/login', '/unknown', '/\nmodels']) assert.equal(safeReturnPath(path), '/app/articles');
  assert.equal(safeReturnPath('/roadmaps/route?package_id=package%20A'), '/roadmaps/route?package_id=package%20A'); assert.equal(safeReturnPath('/models'), '/models');
});

class Element {
  constructor(doc) { this.ownerDocument = doc; this.events = new EventTarget(); this.children = []; this.value = ''; this.attrs = {}; }
  setAttribute(k, v) { this.attrs[k] = v; } replaceChildren(...nodes) { this.children = nodes; } append(...nodes) { this.children.push(...nodes); }
  addEventListener(...args) { this.events.addEventListener(...args); } removeEventListener(...args) { this.events.removeEventListener(...args); } focus() { this.focused = true; }
}
function fixture(api) {
  const doc = { createElement: () => new Element(doc) }, root = new Element(doc), form = new Element(doc), username = new Element(doc), password = new Element(doc), error = new Element(doc), button = new Element(doc);
  const fields = { '[name="username"]': username, '[name="password"]': password, '[type="submit"]': button, '#login-error': error }; form.querySelector = key => fields[key]; root.querySelector = () => form;
  let generation = 0; const session = { get snapshot() { return { generation, phase: 'ready', user: null }; }, subscribe: () => () => {}, refresh: async () => {} };
  const scope = createPageScope(session), routes = []; const dispose = mountLogin(root, { scope, api, identity: session.snapshot, session, next: '/models', navigate: path => routes.push(path) });
  return { root, form, username, password, error, button, scope, session, routes, dispose, changeGeneration() { generation++; } };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
test('login rejects empty input, disables duplicate submit and keeps failed credentials feedback in the active form', async () => {
  let calls = 0, reject;
  const f = fixture({ login: (_command, { signal }) => { calls++; assert.ok(signal instanceof AbortSignal); return new Promise((_r, no) => { reject = no; }); } });
  const submit = () => f.form.events.dispatchEvent(new Event('submit', { cancelable: true }));
  submit(); assert.equal(calls, 0); assert.match(f.error.textContent, /账号/);
  f.username.value = 'Fictional'; f.password.value = 'Fictional pass'; submit(); submit(); assert.equal(calls, 1); assert.equal(f.button.disabled, true);
  reject({ status: 401 }); await tick(); assert.match(f.error.textContent, /账号或密码/); assert.equal(f.username.value, 'Fictional'); assert.equal(f.button.disabled, false); assert.deepEqual(f.routes, []);
  f.scope.dispose(); assert.equal(f.password.value, ''); assert.equal(f.username.value, '');
});
test('unmounted login never redirects or restores a password after a late result', async () => {
  let resolve; const f = fixture({ login: () => new Promise(r => { resolve = r; }) });
  f.username.value = 'Fictional'; f.password.value = 'Fictional pass'; f.form.events.dispatchEvent(new Event('submit', { cancelable: true }));
  f.dispose(); assert.equal(f.scope.signal.aborted, true); resolve({ id: 'fictional-user', role: 'owner' }); await tick(); assert.deepEqual(f.routes, []); assert.equal(f.password.value, '');
});
