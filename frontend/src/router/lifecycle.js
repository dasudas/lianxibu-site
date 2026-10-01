import { resolveRoute } from './routes.js';
import { createPageScope } from '../state/page-scope.js';

export function createPageRouter({ browser, session, mount, onError = () => {} }) {
  let started = false, disposed = false, scope = null, unmount = null, mountedUrl = null;
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
      mountedUrl = url.href;
      const cleanup = mount({ route: resolveRoute(url.pathname), url, scope, session: session.snapshot });
      if (cleanup !== undefined && typeof cleanup !== 'function') throw new TypeError('Page mount must return a synchronous disposer');
      unmount = cleanup ?? null;
    } catch (error) { scope.dispose(); onError(error); }
  }
  const unsubscribe = session.subscribe(render);
  const canLeave = () => !disposed && (scope?.canLeave() ?? true);
  function popstate() {
    if (!canLeave()) { if (mountedUrl) browser.history.pushState(null, '', mountedUrl); return; }
    render();
  }
  function beforeunload(event) {
    if (scope?.hasUnsavedChanges) { event.preventDefault(); event.returnValue = ''; }
  }
  return Object.freeze({
    canLeave,
    start() { if (started || disposed) return; started = true; browser.events.addEventListener('popstate', popstate); browser.events.addEventListener('beforeunload', beforeunload); render(); },
    navigate(path, { replace = false } = {}) {
      if (disposed) return;
      const url = new URL(path, browser.location.href);
      if (url.origin !== new URL(browser.location.href).origin || !['http:', 'https:'].includes(url.protocol)) throw new TypeError('Navigation must stay on this site');
      if (!canLeave()) return false;
      browser.history[replace ? 'replaceState' : 'pushState'](null, '', url.href); render();
      return true;
    },
    dispose() { if (disposed) return; disposed = true; unsubscribe(); browser.events.removeEventListener('popstate', popstate); browser.events.removeEventListener('beforeunload', beforeunload); clear(); }
  });
}
