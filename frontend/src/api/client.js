const PREFIX = '/api/business/v1';
const RETRYABLE = new Set([429, 503]);

export class ApiError extends Error {
  constructor(status, code, message, requestId = null) {
    super(message || code || `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code || 'http_error';
    this.requestId = requestId;
  }
}

export class StaleSessionError extends Error {
  constructor() {
    super('Request belongs to an earlier session');
    this.name = 'StaleSessionError';
  }
}

function validPath(path) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')
      || path.includes('..') || path.includes('#')) {
    throw new TypeError('API path must be a relative absolute-path');
  }
  return path;
}

function retryDelay(value, now, attempt) {
  if (value) {
    const seconds = Number(value);
    if (Number.isFinite(seconds) && seconds >= 0) return Math.min(seconds * 1000, 30_000);
    const date = Date.parse(value);
    if (Number.isFinite(date)) return Math.min(Math.max(date - now(), 0), 30_000);
  }
  return 250 * (2 ** attempt);
}

export function createApiClient({
  fetchImpl = globalThis.fetch,
  sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)),
  now = Date.now,
  makeKey = () => globalThis.crypto.randomUUID()
} = {}) {
  let csrfToken = null;
  let generation = 0;
  const inFlight = new Set();
  const sessionListeners = new Set();

  function subscribeSessionChange(listener) {
    if (typeof listener !== 'function') throw new TypeError('Session listener must be a function');
    sessionListeners.add(listener);
    return () => sessionListeners.delete(listener);
  }

  function clearSession(reason = 'cleared') {
    generation += 1;
    csrfToken = null;
    for (const controller of inFlight) controller.abort();
    inFlight.clear();
    const event = Object.freeze({ generation, reason });
    for (const listener of [...sessionListeners]) {
      // A broken consumer must not prevent other views from clearing private data.
      try { listener(event); } catch { /* Isolation at the invalidation boundary. */ }
    }
  }

  async function request(method, path, { body, query, idempotencyKey, signal } = {}) {
    method = method.toUpperCase();
    validPath(path);
    const write = !['GET', 'HEAD'].includes(method);
    if (write && !csrfToken) throw new ApiError(0, 'csrf_required', 'Session CSRF token is required');

    const key = write && !(method === 'DELETE' && path === '/session')
      ? (idempotencyKey ?? makeKey()) : null;
    if (key !== null && (!/^[\x21-\x7e]{1,128}$/.test(key))) {
      throw new TypeError('Invalid Idempotency-Key');
    }
    const serialized = body === undefined ? undefined : JSON.stringify(body);
    const params = new URLSearchParams();
    for (const [name, value] of Object.entries(query ?? {})) {
      if (value !== undefined && value !== null) params.set(name, String(value));
    }
    const url = PREFIX + path + (params.size ? `?${params}` : '');
    const headers = { Accept: 'application/json' };
    if (serialized !== undefined) headers['Content-Type'] = 'application/json';
    if (write) headers['X-CSRF-Token'] = csrfToken;
    if (key !== null) headers['Idempotency-Key'] = key;
    const capturedGeneration = generation;
    const controller = new AbortController();
    const abort = () => controller.abort();
    inFlight.add(controller);
    if (signal) {
      if (signal.aborted) controller.abort();
      else signal.addEventListener('abort', abort, { once: true });
    }
    try {
      for (let attempt = 0; attempt < 3; attempt += 1) {
        if (capturedGeneration !== generation) throw new StaleSessionError();
        controller.signal.throwIfAborted();
        const response = await fetchImpl(url, {
          method, headers, body: serialized, credentials: 'same-origin',
          mode: 'same-origin', redirect: 'error', cache: 'no-store',
          signal: controller.signal
        });
        if (capturedGeneration !== generation) throw new StaleSessionError();
        controller.signal.throwIfAborted();
        if (RETRYABLE.has(response.status) && attempt < 2 && (!write || key !== null)) {
          await sleep(retryDelay(response.headers?.get('Retry-After'), now, attempt));
          continue;
        }
        const envelope = response.status === 204 ? null : await response.json();
        if (capturedGeneration !== generation) throw new StaleSessionError();
        controller.signal.throwIfAborted();
        if (response.status === 401) clearSession('unauthorized');
        if (!response.ok) {
          throw new ApiError(response.status, envelope?.error?.code,
            envelope?.error?.message, envelope?.meta?.request_id);
        }
        if (response.status !== 204 && (!envelope || !Object.hasOwn(envelope, 'data'))) {
          throw new ApiError(response.status, 'invalid_response', 'Response is missing data');
        }
        return envelope;
      }
      throw new ApiError(503, 'retry_exhausted', 'Temporary failure');
    } catch (error) {
      if (capturedGeneration !== generation && !(error instanceof ApiError && error.status === 401)) {
        throw new StaleSessionError();
      }
      throw error;
    } finally {
      inFlight.delete(controller);
      signal?.removeEventListener('abort', abort);
    }
  }

  async function getSession() {
    const capturedGeneration = generation;
    const response = await request('GET', '/session');
    if (capturedGeneration !== generation) throw new StaleSessionError();
    csrfToken = response.data.csrf_token || null;
    return response.data;
  }

  async function list(path, { page = 1, pageSize = 20, query = {} } = {}) {
    if (!Number.isInteger(page) || page < 1 || !Number.isInteger(pageSize)
        || pageSize < 1 || pageSize > 50) {
      throw new RangeError('Invalid pagination');
    }
    const response = await request('GET', path, {
      query: { ...query, page, page_size: pageSize }
    });
    const data = response.data;
    if (!data || !Array.isArray(data.items) || !Number.isInteger(data.total)) {
      throw new ApiError(200, 'invalid_response', 'Invalid list response');
    }
    return data;
  }

  return Object.freeze({ request, getSession, list, clearSession, subscribeSessionChange, get generation() { return generation; } });
}
