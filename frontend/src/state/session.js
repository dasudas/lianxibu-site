export function createSessionState(api) {
  let generation = 0, sequence = 0, disposed = false;
  let snapshot = Object.freeze({ generation, phase: 'loading', user: null });
  const listeners = new Set(), privateMemory = new Map();
  function publish(phase, user = null) {
    snapshot = Object.freeze({ generation, phase, user });
    for (const listener of [...listeners]) {
      try { listener(snapshot); } catch { /* Every consumer must receive invalidation. */ }
    }
  }
  const unsubscribe = api.subscribeSessionChange(({ reason }) => {
    if (disposed) return;
    generation++; sequence++; privateMemory.clear();
    publish(reason === 'refresh' ? 'loading' : 'unauthorized');
  });
  async function refresh() {
    if (disposed) return;
    api.clearSession('refresh');
    const captured = ++sequence;
    try {
      const data = await api.getSession();
      if (disposed || captured !== sequence) return;
      if (typeof data.authenticated !== 'boolean' || (data.authenticated
          && (typeof data.user_id !== 'string' || !data.user_id || !['user', 'owner'].includes(data.role)))) {
        throw new TypeError('Invalid session response');
      }
      const user = data.authenticated ? Object.freeze({ id: data.user_id, role: data.role }) : null;
      publish('ready', user);
    } catch (error) {
      if (disposed || captured !== sequence) return;
      publish(error.status === 401 ? 'unauthorized' : 'error');
    }
  }
  return Object.freeze({
    get snapshot() { return snapshot; }, refresh,
    clear() { if (!disposed) api.clearSession(); },
    subscribe(listener) {
      if (typeof listener !== 'function') throw new TypeError('Listener required');
      if (!disposed) listeners.add(listener);
      return () => listeners.delete(listener);
    },
    remember(key, value) { if (!disposed && snapshot.user) privateMemory.set(key, value); },
    recall(key) { return privateMemory.get(key); },
    dispose() {
      if (disposed) return;
      api.clearSession(); disposed = true; unsubscribe(); listeners.clear(); privateMemory.clear();
    }
  });
}
