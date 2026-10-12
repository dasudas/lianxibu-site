import assert from 'node:assert/strict';
import { createApiClient } from '../../src/api/client.js';
import { createIdentityApi } from '../../src/api/identity.js';

// Invoked by the Java integration test against its temporary loopback HTTP bridge.
const base = new URL(process.argv[2]);
assert.equal(base.hostname, '127.0.0.1');
const cookies = new Map(); let posts = 0;
const client = createApiClient({ fetchImpl: async (path, options) => {
  const headers = { ...options.headers, Origin: 'https://site.example.invalid' };
  if (cookies.size) headers.Cookie = [...cookies].map(([k,v]) => `${k}=${v}`).join('; ');
  if (options.method === 'POST') posts++;
  const result = await fetch(new URL(path, base), { ...options, headers });
  for (const cookie of result.headers.getSetCookie()) {
    assert.match(cookie, /Secure/i); assert.match(cookie, /HttpOnly/i); assert.match(cookie, /SameSite=/i);
    const pair = cookie.split(';')[0], i = pair.indexOf('='), name = pair.slice(0, i), value = pair.slice(i + 1);
    if (!value || /Max-Age=0(?:;|$)/i.test(cookie)) cookies.delete(name); else cookies.set(name, value);
  }
  return result;
} });
const api = createIdentityApi(client);
await client.getSession();
await assert.rejects(api.login({ username: 'fictional', password: 'wrong' }), e => e.status === 401);
// The real Java service consumed the first anonymous challenge; no page reload occurs.
assert.deepEqual(await api.login({ username: 'fictional', password: 'correct' }), { id: 'fictional-user', role: 'user' });
assert.equal(posts, 2);
const session = await client.getSession(); assert.equal(session.authenticated, true); assert.equal(session.user_id, 'fictional-user');
assert.equal(cookies.has('lianxibu_anonymous'), false);
await client.endSession(); assert.equal((await client.getSession()).authenticated, false);
console.log('real Java challenge consumption, frontend retry, cookies and logout passed');
