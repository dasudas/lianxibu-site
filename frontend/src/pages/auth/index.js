import { createStatusView } from '../../components/common/status-view.js';
import { safeReturnPath } from '../../router/return-path.js';

export const loginMarkup = '<div class="login-wrap"><div class="login-intro"><span class="brand-mark" aria-hidden="true"><svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="m8 6-6 6 6 6m8-12 6 6-6 6M14 3l-4 18"/></svg></span><h1>把今天的好奇，<br>留给明天的自己。</h1><p>你的资讯、学习计划和私人笔记，<br>在一个安静的空间里。</p></div><form class="login-card" novalidate><h2>进入我的工作空间</h2><p>使用站点账号登录，不开放公众注册。</p><label class="field">账号<input name="username" autocomplete="username" required /></label><label class="field">密码<input name="password" type="password" autocomplete="current-password" required /></label><p class="field-error" id="login-error" role="alert"></p><button class="button primary full" type="submit">登录工作空间</button><small>账号由站长管理。密码遗失请联系站长。</small></form></div>';

export function mountLogin(root, { scope, api, identity, session, next, navigate }) {
  const destination = safeReturnPath(next); let disposed = false, busy = false;
  if (identity.phase === 'loading' || identity.phase === 'error') {
    const status = createStatusView(root); scope.own(() => status.dispose());
    status.show(identity.phase === 'loading' ? 'loading' : 'error', { message: identity.phase === 'loading' ? '正在确认登录状态…' : '身份服务暂时不可用，请重试。', retry: identity.phase === 'error' ? () => session.refresh() : undefined });
    return () => { scope.dispose(); status.dispose(); };
  }
  if (identity.user) {
    const status = createStatusView(root); scope.own(() => status.dispose());
    status.show('empty', { message: '你已登录，可返回工作空间。' });
    const button = root.ownerDocument.createElement('button'); button.type = 'button'; button.textContent = '返回工作空间'; scope.listen(button, 'click', () => navigate(destination)); root.append(button);
    return () => { scope.dispose(); status.dispose(); };
  }
  root.innerHTML = loginMarkup;
  const form = root.querySelector('form'), username = form.querySelector('[name="username"]'), password = form.querySelector('[name="password"]'), error = form.querySelector('#login-error'), button = form.querySelector('[type="submit"]');
  function dispose() { if (disposed) return; disposed = true; scope.dispose(); username.value = ''; password.value = ''; root.replaceChildren(); root.innerHTML = ''; }
  scope.own(dispose);
  function waiting(value) { busy = value; button.disabled = value; button.textContent = value ? '正在登录…' : '登录工作空间'; form.setAttribute('aria-busy', String(value)); }
  scope.listen(form, 'submit', event => {
    event.preventDefault(); if (busy || disposed || !scope.active) return;
    error.textContent = '';
    if (!username.value || [...username.value].length > 64) { error.textContent = '请输入账号，最多64个字符。'; username.focus(); return; }
    if (!password.value || [...password.value].length > 1024) { error.textContent = '请输入密码，最多1024个字符。'; password.focus(); return; }
    waiting(true);
    scope.run('login', signal => api.login({ username: username.value, password: password.value }, { signal }), async user => {
      password.value = ''; error.textContent = '正在确认登录…';
      await session.refresh();
      const current = session.snapshot.user;
      if (current?.id === user.id && current.role === user.role) navigate(destination);
    }, failure => {
      waiting(false);
      if (failure.status === 401) error.textContent = '账号或密码不匹配，请检查后重试。';
      else if (failure.status === 403) error.textContent = '登录验证已失效，请刷新页面后重试。';
      else if (failure.status === 429) error.textContent = '尝试过于频繁，请稍后重试。';
      else error.textContent = '登录暂时无法确认，请稍后重试。';
    });
  });
  return dispose;
}
