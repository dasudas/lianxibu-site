import { resolveRoute } from './routes.js';

export function safeReturnPath(value, fallback = '/app/articles') {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.length > 4096 || /[\u0000-\u001f\u007f]/.test(value)) return fallback;
  try {
    const url = new URL(value, 'https://same-site.example.invalid');
    const route = resolveRoute(url.pathname);
    if (url.origin !== 'https://same-site.example.invalid' || !route || route.id === 'UI-02') return fallback;
    return url.pathname + url.search + url.hash;
  } catch { return fallback; }
}
