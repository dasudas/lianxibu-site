const groups = [
  ['发现与阅读', [['/', '开发知识图谱', 'book'], ['/weekly-reports', '周报与月报', 'calendar'], ['/models', '模型解读', 'spark'], ['/agents', 'Agent 解读', 'code'], ['/skills', 'Skill 解读', 'layers'], ['/workflows', 'AI 开发方案', 'globe']]],
  ['我的空间', [['/app/articles', '资讯池', 'layers'], ['/app/learning', '学习记录', 'book']]],
  ['站点管理', [['/admin/digests', '内容审核', 'check'], ['/admin/sources', '信息源', 'globe'], ['/admin/jobs', '任务与用量', 'activity'], ['/admin/preferences', '偏好设置', 'settings']]]
];
const icons = {
  book: '<path d="M4 4h6l2 2 2-2h6v15h-6l-2 2-2-2H4zM12 6v15"/>', code: '<path d="m8 6-6 6 6 6m8-12 6 6-6 6M14 3l-4 18"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 2v6m10-6v6M3 11h18"/>', spark: '<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z"/>',
  layers: '<path d="m12 3 10 5-10 5L2 8zm-10 9 10 5 10-5M2 17l10 5 10-5"/>', globe: '<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18"/>',
  check: '<path d="m5 12 4 4L19 6"/>', activity: '<path d="M2 12h5l3-8 4 16 3-8h5"/>', settings: '<path d="M3 6h18M3 12h18M3 18h18"/><circle cx="8" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="9" cy="18" r="2"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>', arrow: '<path d="M5 12h14m-5-5 5 5-5 5"/>', menu: '<path d="M4 6h16M4 12h16M4 18h16"/>', logout: '<path d="M9 3H4v18h5m5-15 6 6-6 6m-6-6h12"/>'
};
const icon = name => `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name] ?? icons.book}</svg>`;
function active(path, href) {
  if (href === '/') return path === '/' || path.startsWith('/digests/');
  if (href === '/weekly-reports') return /^\/(weekly|monthly)-reports(?:\/|$)/.test(path);
  return path === href || path.startsWith(href + '/');
}
export function shellMarkup({ pathname = '/', user = null } = {}) {
  const owner = user?.role === 'owner';
  const visible = groups.filter((_, index) => index !== 2 || owner);
  const pageName = visible.flatMap(([, items]) => items).find(([href]) => active(pathname, href))?.[1] ?? (pathname === '/login' ? '登录' : pathname.startsWith('/roadmaps') ? '学习路线' : '内容详情');
  return `<a class="skip-link" href="#main">跳到主内容</a><aside class="sidebar" id="sidebar"><a class="brand" href="/" data-route><span class="brand-mark">${icon('code')}</span><span>练习簿<small>让好奇心，成为行动</small></span></a><nav aria-label="主导航">${visible.map(([label, items]) => `<div class="nav-group"><p>${label}</p>${items.map(([href, title, symbol]) => `<a class="nav-item${active(pathname, href) ? ' active' : ''}" href="${href}" data-route${active(pathname, href) ? ' aria-current="page"' : ''}>${icon(symbol)}<span>${title}</span></a>`).join('')}</div>`).join('')}</nav><div class="sidebar-bottom">${user ? `<button class="account" type="button" data-logout>${icon('user')}<span>${owner ? '站长工作空间' : '我的工作空间'}<small>退出登录</small></span>${icon('logout')}</button>` : `<a class="account" href="/login" data-route>${icon('user')}<span>进入我的工作空间<small>登录后管理收藏与学习记录</small></span>${icon('arrow')}</a>`}</div></aside><div class="workspace"><header class="topbar"><button type="button" class="icon-button mobile-menu" data-menu aria-label="展开导航" aria-expanded="false" aria-controls="sidebar">${icon('menu')}</button><div class="breadcrumb">练习簿 <span>/</span> ${pageName}</div><div class="top-actions">${user ? '<span class="avatar" title="已登录">练</span>' : '<a class="small-link" href="/login" data-route>登录</a>'}</div></header><main id="main" tabindex="-1"></main><footer class="site-footer"><p class="footer-brand">练习簿</p><p>记录与交流技术见闻。</p><p>引用内容的版权归原作者或权利人所有。</p></footer></div>`;
}

export function mountShell(root, { pathname, user, scope, navigate, logout }) {
  root.innerHTML = shellMarkup({ pathname, user });
  const main = root.querySelector('#main');
  scope.own(() => { root.ownerDocument?.body.classList.remove('menu-open'); root.innerHTML = ''; });
  scope.listen(root, 'click', event => {
    if (event.defaultPrevented || event.button > 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    if (event.target.closest('[data-logout]')) { logout?.(); return; }
    const menu = event.target.closest('[data-menu]');
    if (menu) {
      const expanded = menu.getAttribute('aria-expanded') !== 'true';
      menu.setAttribute('aria-expanded', String(expanded)); root.querySelector('#sidebar').classList.toggle('open', expanded);
      root.ownerDocument.body.classList.toggle('menu-open', expanded); return;
    }
    const anchor = event.target.closest('a[data-route]'); if (!anchor) return;
    const href = anchor.getAttribute('href'); if (!href?.startsWith('/') || href.startsWith('//')) return;
    event.preventDefault(); navigate(href);
  });
  return main;
}
