const messages = Object.freeze({ loading: '正在加载…', empty: '暂无内容。', error: '暂时无法加载，请重试。', unauthorized: '请登录后查看。', unpublished: '内容尚未发布。', withdrawn: '此内容已撤回。', version_conflict: '内容已更新，请重新加载。' });

export function createStatusView(root) {
  let cleanup = () => {}, disposed = false;
  function show(phase, { message = messages[phase], retry } = {}) {
    if (disposed) return;
    if (phase !== 'ready' && !Object.hasOwn(messages, phase)) throw new TypeError('Unknown view phase');
    cleanup(); cleanup = () => {};
    root.setAttribute('aria-busy', phase === 'loading' ? 'true' : 'false');
    root.replaceChildren();
    if (phase === 'ready') return;
    const text = root.ownerDocument.createElement('p');
    text.setAttribute('role', phase === 'error' || phase === 'version_conflict' ? 'alert' : 'status');
    text.textContent = message; root.append(text);
    if (typeof retry === 'function') {
      const button = root.ownerDocument.createElement('button');
      button.type = 'button'; button.textContent = '重试';
      button.addEventListener('click', retry); cleanup = () => button.removeEventListener('click', retry);
      root.append(button);
    }
  }
  return Object.freeze({ show, dispose() { if (disposed) return; disposed = true; cleanup(); root.replaceChildren(); root.setAttribute('aria-busy', 'false'); } });
}
