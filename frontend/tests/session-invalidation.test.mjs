import test from 'node:test';
import assert from 'node:assert/strict';
import { createApiClient, ApiError } from '../src/api/client.js';

test('clear and 401 synchronously invalidate every live subscriber', async () => {
  const client = createApiClient({ fetchImpl: async () => ({ status: 401, ok: false, json: async () => ({ error: { code: 'not_authenticated' } }) }) });
  const events = [];
  client.subscribeSessionChange(() => { throw new Error('broken view'); });
  const stop = client.subscribeSessionChange(event => events.push(event));
  client.clearSession();
  assert.deepEqual(events, [{ generation: 1, reason: 'cleared' }]);
  await assert.rejects(client.request('GET', '/private/articles'), ApiError);
  assert.deepEqual(events[1], { generation: 2, reason: 'unauthorized' });
  assert.ok(Object.isFrozen(events[1]));
  stop(); stop(); client.clearSession();
  assert.equal(events.length, 2);
});

test('cancel during retry never sends another request even when transport ignores abort', async () => {
  let calls = 0;
  const controller = new AbortController();
  const client = createApiClient({ fetchImpl: async () => { calls++; return { status: 503, ok: false, headers: { get: () => '0' } }; }, sleep: async () => controller.abort() });
  await assert.rejects(client.request('GET', '/home', { signal: controller.signal }), error => error.name === 'AbortError');
  assert.equal(calls, 1);
});

test('unmounted consumer cannot receive a late decoded response', async () => {
  const controller = new AbortController();
  const client = createApiClient({ fetchImpl: async () => ({ status: 200, ok: true, json: async () => { controller.abort(); return { data: { note: 'Fictional private' } }; } }) });
  await assert.rejects(client.request('GET', '/private/articles', { signal: controller.signal }), error => error.name === 'AbortError');
});
