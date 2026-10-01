import test from 'node:test';
import assert from 'node:assert/strict';
import { shellMarkup, mountShell } from '../src/components/layout/shell.js';

test('existing shell retains navigation, hero workspace and role-specific account entrances', () => {
  const anonymous = shellMarkup({ pathname: '/' });
  assert.match(anonymous, /class="sidebar"/); assert.match(anonymous, /class="workspace"/); assert.match(anonymous, /让好奇心，成为行动/);
  assert.match(anonymous, /href="\/"[^>]*aria-current="page"/); assert.match(anonymous, /href="\/login"/);
  assert.doesNotMatch(anonymous, /href="\/admin\/sources"/);
  const owner = shellMarkup({ pathname: '/admin/sources', user: { id: 'Fictional <script>', role: 'owner' } });
  assert.match(owner, /href="\/admin\/sources"[^>]*aria-current="page"/); assert.match(owner, /data-logout/); assert.doesNotMatch(owner, /Fictional|<script>/);
  const user = shellMarkup({ pathname: '/app/articles', user: { role: 'user' } }); assert.doesNotMatch(user, /href="\/admin\//); assert.match(user, /我的工作空间/);
  assert.doesNotMatch(owner, /演示|2026|微信|auther|个人账号/); assert.match(owner, /跳到主内容/); assert.match(owner, /id="main" tabindex="-1"/);
});

test('shell delegates ordinary same-site links and removes listeners/resources on unmount', () => {
  const handlers = new Map(), owned = []; const root = { innerHTML: '', querySelector: () => ({}), addEventListener(type, fn) { handlers.set(type, fn); }, removeEventListener(type) { handlers.delete(type); } };
  const scope = { own(fn) { owned.push(fn); }, listen(target, type, fn) { target.addEventListener(type, fn); owned.push(() => target.removeEventListener(type, fn)); } };
  const navigated = []; let logout = 0;
  mountShell(root, { pathname: '/', scope, navigate: path => navigated.push(path), logout: () => logout++ });
  const event = href => ({ button: 0, defaultPrevented: false, preventDefault() { this.prevented = true; }, target: { closest(selector) { return selector === 'a[data-route]' ? { getAttribute: () => href } : null; } } });
  handlers.get('click')(event('/models')); handlers.get('click')(event('https://elsewhere.invalid/')); handlers.get('click')({ ...event('/agents'), ctrlKey: true });
  assert.deepEqual(navigated, ['/models']);
  handlers.get('click')({ button: 0, target: { closest: selector => selector === '[data-logout]' ? {} : null } }); assert.equal(logout, 1);
  for (const dispose of owned.reverse()) dispose(); assert.equal(root.innerHTML, ''); assert.equal(handlers.size, 0);
});
