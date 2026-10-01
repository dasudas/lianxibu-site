import test from 'node:test';
import assert from 'node:assert/strict';
import { createApiClient } from '../src/api/client.js';
import { createIdentityApi } from '../src/api/identity.js';
const response = (status, data) => ({ status, ok: status === 200, json: async () => status >= 400 ? { error: { code: status === 401 ? 'unauthenticated' : 'temporarily_unavailable' } } : { data } });
test('login sends the exact credential command once without synthetic idempotency and projects no token', async () => {
  let submitted;
  const client = createApiClient({ fetchImpl: async (_u, options) => { if (options.method === 'GET') return response(200, { csrf_token: 'fictional-anonymous' }); submitted = options; return response(200, { authenticated: true, user_id: 'fictional-user', role: 'user', csrf_token: 'fictional-new' }); } });
  await client.getSession(); const user = await createIdentityApi(client).login({ username: 'Fictional', password: ' Fictional pass ' });
  assert.deepEqual(user, { id: 'fictional-user', role: 'user' }); assert.ok(Object.isFrozen(user)); assert.equal(submitted.headers['Idempotency-Key'], undefined); assert.equal(submitted.headers['X-CSRF-Token'], 'fictional-anonymous'); assert.equal(submitted.body, JSON.stringify({ username: 'Fictional', password: ' Fictional pass ' }));
});
test('credential rejection retains anonymous session for feedback and session POST never automatically retries', async () => {
  for (const status of [401, 503]) {
    let writes = 0; const client = createApiClient({ fetchImpl: async (_u, options) => options.method === 'GET' ? response(200, { csrf_token: 'fictional' }) : (writes++, response(status)), sleep: async () => {} });
    await client.getSession(); await assert.rejects(createIdentityApi(client).login({ username: 'Fictional', password: 'Fictional' }), e => e.status === status); assert.equal(writes, 1); assert.equal(client.generation, 0);
  }
});
test('invalid credentials are rejected before transport and invalid authenticated identity is refused', async () => {
  let calls = 0; const api = createIdentityApi({ request: async () => { calls++; return { data: { authenticated: true, user_id: null, role: 'owner' } }; } });
  await assert.rejects(api.login({ username: '', password: 'Fictional' }), TypeError); await assert.rejects(api.login({ username: 'Fictional', password: 'x'.repeat(1025) }), TypeError); assert.equal(calls, 0);
  await assert.rejects(api.login({ username: 'Fictional', password: 'Fictional' }), e => e.code === 'invalid_response');
});
