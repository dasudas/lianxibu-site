import { ApiError } from './client.js';
import { learningObjectPath } from './paths.js';

const invalid = () => { throw new ApiError(200, 'invalid_response', 'Learning identity cannot be verified'); };
function id(value) { if (typeof value !== 'string' || !value || [...value].length > 64 || /[\u0000-\u001f\u007f]/.test(value)) invalid(); return value; }
const result = (state, href = null, routeTitle = null) => Object.freeze({ state, href, routeTitle });
function object(value, task) { if (!value || value.package_id !== task.packageId || value.id !== task.id) invalid(); return value; }

export function createLearningReferenceApi(client) {
  return Object.freeze({ async lookup(task, { signal } = {}) {
    if (!task) return result('absent');
    id(task.packageId); id(task.id); if (!['node', 'question', 'project'].includes(task.kind)) invalid();
    try {
      signal?.throwIfAborted();
      const { data } = await client.request('GET', learningObjectPath(task.packageId, task.kind, task.id), { signal });
      const value = object(data, task), query = `package_id=${encodeURIComponent(task.packageId)}`;
      if (task.kind !== 'node') return result('available', `/${task.kind}s/${encodeURIComponent(task.id)}?${query}`);
      const moduleId = id(value.module_id), seen = new Set(); let expectedTotal = null;
      // Bound metadata work. Exhausting this search is unknown, never proof of withdrawal.
      for (let page = 1; page <= 100; page++) {
        signal?.throwIfAborted();
        const response = await client.request('GET', `/learning/packages/${encodeURIComponent(task.packageId)}/routes`, { signal, query: { page, page_size: 50 } });
        const routes = response.data;
        if (!routes || routes.page !== page || routes.page_size !== 50 || !Number.isSafeInteger(routes.total) || routes.total < 0
            || !Array.isArray(routes.items) || routes.items.length > 50 || routes.items.length > routes.total) invalid();
        if (expectedTotal !== null && expectedTotal !== routes.total) invalid(); expectedTotal = routes.total;
        for (const route of routes.items) {
          if (!route || route.package_id !== task.packageId || !Array.isArray(route.module_ids) || route.module_ids.length > 1000
              || typeof route.title !== 'string' || !route.title || [...route.title].length > 200) invalid();
          const routeId = id(route.id); if (seen.has(routeId)) invalid(); seen.add(routeId);
          const modules = route.module_ids.map(id);
          if (modules.includes(moduleId)) return result('available', `/roadmaps/${encodeURIComponent(routeId)}/nodes/${encodeURIComponent(task.id)}?${query}`, route.title);
        }
        if (page * 50 >= routes.total) return result('unavailable');
        if (routes.items.length === 0) invalid();
      }
      return result('unknown');
    } catch (error) {
      if (error.name === 'AbortError' || error.name === 'StaleSessionError') throw error;
      return result(error.status === 404 || error.status === 410 ? 'unavailable' : 'unknown');
    }
  } });
}
