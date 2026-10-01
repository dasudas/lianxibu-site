export function createPageScope(session, { onCleanupError = () => {} } = {}) {
  const controller = new AbortController(), resources = [], requests = new Map();
  const generation = session.snapshot.generation;
  let active = true;
  const current = () => active && generation === session.snapshot.generation;
  function own(dispose) {
    if (typeof dispose !== 'function') throw new TypeError('Disposer required');
    if (active) resources.push(dispose); else dispose();
    return dispose;
  }
  function dispose() {
    if (!active) return;
    active = false; controller.abort(); requests.clear();
    const errors = [];
    for (const cleanup of resources.reverse()) { try { cleanup(); } catch (error) { errors.push(error); } }
    resources.length = 0;
    if (errors.length) { try { onCleanupError(errors); } catch { /* Cleanup must finish. */ } }
  }
  own(session.subscribe(() => { if (!current()) dispose(); }));
  return Object.freeze({
    get active() { return current(); }, signal: controller.signal, own, dispose,
    listen(target, type, listener, options) {
      const guarded = event => { if (current()) listener(event); };
      target.addEventListener(type, guarded, options);
      own(() => target.removeEventListener(type, guarded, options));
    },
    async run(key, operation, commit, reject = () => {}) {
      if (!current()) return;
      const token = Symbol(key); requests.set(key, token);
      const valid = () => current() && requests.get(key) === token;
      try {
        const value = await operation(controller.signal);
        if (valid()) commit(value);
      } catch (error) {
        if (valid() && error.name !== 'AbortError' && error.name !== 'StaleSessionError') reject(error);
      } finally {
        if (requests.get(key) === token) requests.delete(key);
      }
    }
  });
}
