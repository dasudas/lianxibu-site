import { resolveRoute } from './routes.js';
import { createPageScope } from '../state/page-scope.js';

export function createPageRouter({ browser, session, mount, onError = () => {} }) {
  let started = false, disposed = false, scope = null, unmount = null;
  function clear() {
    const previousUnmount = unmount, previousScope = scope;
    unmount = null; scope = null;
    try { previousUnmount?.(); } catch (error) { onError(error); }
    finally { previousScope?.dispose(); }
  }
  function render() {
    if (!started || disposed) return;
    clear();
    scope = createPageScope(session, { onCleanupError: errors => onError(new AggregateError(errors)) });
    try {
      const url = new URL(browser.location.href);
      const cleanup = mount({ route: resolveRoute(url.pathname), url, scope, session: session.snapshot });
      if (cleanup !== undefined && typeof cleanup !== 'function') throw new TypeError('Page mount must return a synchronous disposer');
      unmount = cleanup ?? null;
    } catch (error) { scope.dispose(); onError(error); }
  }
  const unsubscribe = session.subscribe(render);
  return Object.freeze({
    start() { if (started || disposed) return; started = true; browser.events.addEventListener('popstate', render); render(); },
    navigate(path, { replace = false } = {}) {
      if (disposed) return;
      const url = new URL(path, browser.location.href);
      if (url.origin !== new URL(browser.location.href).origin || !['http:', 'https:'].includes(url.protocol)) throw new TypeError('Navigation must stay on this site');
      browser.history[replace ? 'replaceState' : 'pushState'](null, '', url.href); render();
    },
    dispose() { if (disposed) return; disposed = true; unsubscribe(); browser.events.removeEventListener('popstate', render); clear(); }
  });
}
