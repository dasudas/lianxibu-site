const routes = [
  { id: 'UI-01', patterns: [/^\/$/] },
  { id: 'UI-02', patterns: [/^\/login$/] },
  { id: 'UI-03', patterns: [/^\/digests\/[^/]+$/] },
  { id: 'UI-04', patterns: [/^\/(weekly|monthly)-reports$/] },
  { id: 'UI-05', patterns: [/^\/(weekly|monthly)-reports\/[^/]+$/] },
  { id: 'UI-06', patterns: [/^\/app\/articles(?:\/[^/]+)?$/] },
  { id: 'UI-07', patterns: [/^\/app\/learning$/] },
  { id: 'UI-08', patterns: [/^\/(models|agents|skills|workflows)$/] },
  { id: 'UI-09', patterns: [/^\/(models|agents|skills|workflows)\/[^/]+(?:\/revisions\/[^/]+)?$/] },
  { id: 'UI-10', patterns: [/^\/admin\/digests$/] },
  { id: 'UI-11', patterns: [/^\/admin\/sources$/] },
  { id: 'UI-12', patterns: [/^\/admin\/jobs$/] },
  { id: 'UI-13', patterns: [/^\/admin\/preferences$/] },
  { id: 'UI-14', patterns: [/^\/roadmaps$/] },
  { id: 'UI-15', patterns: [/^\/roadmaps\/[^/]+$/] },
  { id: 'UI-16', patterns: [/^\/roadmaps\/[^/]+\/nodes\/[^/]+$/] },
  { id: 'UI-17', patterns: [/^\/questions(?:\/[^/]+)?$/] },
  { id: 'UI-18', patterns: [/^\/projects(?:\/[^/]+)?$/] },
  { id: 'UI-19', patterns: [/^\/app\/learning\/roadmaps$/] }
];

export function resolveRoute(pathname) {
  if (typeof pathname !== 'string' || !pathname.startsWith('/')) return null;
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname;
  return routes.find(route => route.patterns.some(pattern => pattern.test(path))) ?? null;
}

export const routeIds = Object.freeze(routes.map(route => route.id));
