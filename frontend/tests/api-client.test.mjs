import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError, StaleSessionError, createApiClient } from '../src/api/client.js';
import { learningObjectPath, recordPath, recordWriteRequest } from '../src/api/paths.js';

function response(status, value, retryAfter = null) {
  return {
    status, ok: status >= 200 && status < 300,
    headers: { get: () => retryAfter },
    json: async () => value
  };
}

test('list uses stable pagination and session requests use same-origin cookies', async () => {
  const calls = [];
  const client = createApiClient({ fetchImpl: async (url, options) => {
    calls.push({ url, options });
    return response(200, { data: { items: [], page: 2, page_size: 20, total: 0 }, meta: { request_id: 'fixture' } });
  } });
  const result = await client.list('/reports', { page: 2 });
  assert.equal(result.page, 2);
  assert.equal(calls[0].url, '/api/business/v1/reports?page=2&page_size=20');
  assert.equal(calls[0].options.credentials, 'same-origin');
  assert.equal(calls[0].options.redirect, 'error');
  await assert.rejects(client.list('/reports', { pageSize: 51 }), RangeError);
});

test('a retry keeps CSRF, idempotency key, and exact command bytes', async () => {
  const calls = [];
  const delays = [];
  const client = createApiClient({
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      if (url.endsWith('/session')) return response(200, { data: { csrf_token: 'fixture-csrf' } });
      return calls.length === 2
        ? response(503, { error: { code: 'unavailable' } }, '1')
        : response(200, { data: { id: 'fixture-job' } });
    },
    sleep: async delay => { delays.push(delay); },
    makeKey: () => 'fixture-key'
  });
  await client.getSession();
  await client.request('POST', '/admin/jobs', { body: { task_type: 'fixture' } });
  const writes = calls.slice(1);
  assert.equal(writes.length, 2);
  assert.deepEqual(delays, [1000]);
  assert.equal(writes[0].options.headers['X-CSRF-Token'], 'fixture-csrf');
  assert.equal(writes[0].options.headers['Idempotency-Key'], 'fixture-key');
  assert.equal(writes[0].options.body, writes[1].options.body);
  assert.equal(writes[0].options.headers['Idempotency-Key'], writes[1].options.headers['Idempotency-Key']);
});

test('CAS conflicts are surfaced without retry or a replacement key', async () => {
  let writes = 0;
  const client = createApiClient({ fetchImpl: async url => {
    if (url.endsWith('/session')) return response(200, { data: { csrf_token: 'fixture' } });
    writes += 1;
    return response(409, { error: { code: 'revision_conflict', message: 'stale' } });
  } });
  await client.getSession();
  await assert.rejects(client.request('PUT', '/admin/preferences', {
    body: { expected_revision: 3 }
  }), error => error instanceof ApiError && error.code === 'revision_conflict');
  assert.equal(writes, 1);
});

test('expired idempotency results stay visible and are not silently replayed', async () => {
  let writes = 0;
  const client = createApiClient({ fetchImpl: async url => {
    if (url.endsWith('/session')) return response(200, { data: { csrf_token: 'fixture' } });
    writes += 1;
    return response(409, { error: { code: 'idempotency_result_expired' } });
  } });
  await client.getSession();
  await assert.rejects(client.request('POST', '/admin/jobs', { body: { task_type: 'fixture' } }),
    error => error instanceof ApiError && error.code === 'idempotency_result_expired');
  assert.equal(writes, 1);
});

test('429 respects Retry-After for safe reads', async () => {
  let calls = 0;
  const delays = [];
  const client = createApiClient({
    fetchImpl: async () => {
      calls += 1;
      return calls === 1
        ? response(429, { error: { code: 'rate_limited' } }, '2')
        : response(200, { data: { value: 'fixture' } });
    },
    sleep: async delay => { delays.push(delay); }
  });
  await client.request('GET', '/home');
  assert.equal(calls, 2);
  assert.deepEqual(delays, [2000]);
});

test('401 invalidates session and late responses cannot return private data', async () => {
  let resolveLate;
  let calls = 0;
  const client = createApiClient({ fetchImpl: async url => {
    calls += 1;
    if (calls === 1) return response(200, { data: { csrf_token: 'fixture' } });
    if (url.endsWith('/private/articles')) {
      return new Promise(resolve => { resolveLate = resolve; });
    }
    return response(401, { error: { code: 'not_authenticated' } });
  } });
  await client.getSession();
  const late = client.request('GET', '/private/articles');
  await assert.rejects(client.request('GET', '/admin/jobs'), error => error instanceof ApiError && error.status === 401);
  resolveLate(response(200, { data: { items: ['private'] } }));
  await assert.rejects(late, StaleSessionError);
  assert.equal(client.generation, 1);
});

test('learning paths require complete package identity', () => {
  assert.equal(learningObjectPath('package A', 'node', 'node/1'),
    '/learning/packages/package%20A/nodes/node%2F1');
  assert.equal(recordPath('question', 'q1', 'p1'), '/private/records/question/q1');
  assert.deepEqual(recordWriteRequest('question', 'q1', 'p1', { expected_revision: 2 }), {
    path: '/private/records/question/q1',
    body: { expected_revision: 2, package_id: 'p1', object_kind: 'question' }
  });
  assert.throws(() => learningObjectPath('', 'node', 'n1'), TypeError);
  assert.throws(() => recordPath('question', 'q1'), TypeError);
  assert.throws(() => recordWriteRequest('question', 'q1', 'p1', { package_id: 'p2' }), TypeError);
});
