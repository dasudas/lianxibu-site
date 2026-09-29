import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveRoute, routeIds } from '../src/router/routes.js';

const samples = [
  '/', '/login', '/digests/daily-1', '/weekly-reports',
  '/monthly-reports/month-1', '/app/articles/article-1',
  '/app/learning', '/models', '/models/model-1/revisions/old',
  '/admin/digests', '/admin/sources', '/admin/jobs',
  '/admin/preferences', '/roadmaps', '/roadmaps/route-1',
  '/roadmaps/route-1/nodes/node-1', '/questions/question-1',
  '/projects/project-1', '/app/learning/roadmaps'
];

test('all 19 existing page identities have paths', () => {
  assert.equal(routeIds.length, 19);
  assert.equal(new Set(routeIds).size, 19);
  assert.deepEqual(samples.map(path => resolveRoute(path)?.id), routeIds);
});

test('unknown paths do not silently become another page', () => {
  assert.equal(resolveRoute('/missing'), null);
  assert.equal(resolveRoute('/roadmaps/route-1/nodes/'), null);
});
