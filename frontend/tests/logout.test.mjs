import test from 'node:test';
import assert from 'node:assert/strict';
import { createApiClient, StaleSessionError } from '../src/api/client.js';
const response = (status, data) => ({ status, ok: status >= 200 && status < 300, json: async () => status >= 400 ? { error: { code: 'fictional_failure' } } : { data } });
test('logout synchronously clears private consumers but its sole DELETE retains original CSRF and receives server confirmation', async () => {
  let finish, calls = 0, cleared = 0, options;
  const client = createApiClient({ fetchImpl: async (_url, init) => {
    if (init.method === 'GET') return response(200, { csrf_token: 'fictional-csrf' });
    calls++; options = init; return new Promise(resolve => { finish = resolve; });
  } });
  await client.getSession(); client.subscribeSessionChange(() => cleared++);
  const pending = client.endSession(); const duplicate = client.endSession(); assert.equal(pending, duplicate); assert.equal(cleared, 1); assert.equal(client.generation, 1);
  assert.equal(options.headers['X-CSRF-Token'], 'fictional-csrf'); assert.equal(options.credentials, 'same-origin'); assert.equal(options.signal.aborted, false); assert.equal(options.headers['Idempotency-Key'], undefined);
  await assert.rejects(client.getSession(), error => error.code === 'session_transition');
  finish(response(200, { authenticated: false, user_id: null, role: null, csrf_token: 'fictional-anonymous' })); await pending; assert.equal(calls, 1);
});
test('logout rejects service failure and authenticated response without retry or restoring cleared state', async () => {
  for (const reply of [response(503), response(200, { authenticated: true, user_id: 'fictional', role: 'user' })]) {
    let writes = 0;
    const client = createApiClient({ fetchImpl: async (_u, options) => options.method === 'GET' ? response(200, { csrf_token: 'fictional' }) : (writes++, reply) });
    await client.getSession(); await assert.rejects(client.endSession()); assert.equal(client.generation, 1); assert.equal(writes, 1);
    await assert.rejects(client.request('POST', '/admin/jobs', { body: {} }), error => error.code === 'csrf_required');
  }
});
test('old private result stays invalid even when logout endpoint fails', async () => {
  let resolve;
  const client = createApiClient({ fetchImpl: async (url, options) => options.method === 'DELETE' ? response(403) : url.endsWith('/session') ? response(200, { csrf_token: 'fictional' }) : new Promise(r => { resolve = r; }) });
  await client.getSession(); const late = client.request('GET', '/private/articles'); await assert.rejects(client.endSession());
  resolve(response(200, { note: 'Fictional private' })); await assert.rejects(late, StaleSessionError);
});
