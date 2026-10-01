import { createStatusView } from '../../components/common/status-view.js';
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
export function reportListQuery(url) {
  if (url.searchParams.getAll('type').length > 1 || url.searchParams.getAll('page').length > 1) throw new TypeError('Duplicate filter');
  const selected = url.searchParams.get('type'), raw = url.searchParams.get('page') ?? '1';
  if ((selected !== null && selected !== 'daily') || !/^[1-9]\d*$/.test(raw) || !Number.isSafeInteger(Number(raw))) throw new TypeError('Invalid filter');
  return { type: selected ?? (url.pathname === '/monthly-reports' ? 'monthly' : 'weekly'), page: Number(raw) };
}
function pageLink(url, query, page) {
  return `${url.pathname}?${query.type === 'daily' ? 'type=daily&' : ''}page=${page}`;
}
export function reportListMarkup(data, url, query) {
  const pages = Math.max(1, Math.ceil(data.total / data.pageSize));
  const date = value => value === null ? '发布时间未知' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'medium' }).format(new Date(value));
  return `<div class="page-heading"><div><h1>把零散变化，连成一条线</h1><p>回顾每日收获，理解一个阶段内值得关注的方向。</p></div></div><nav class="tabs" aria-label="报告类型">${[['周报','weekly','/weekly-reports'],['月报','monthly','/monthly-reports'],['往期日报','daily','/weekly-reports?type=daily']].map(([name,type,href]) => `<a href="${href}" data-route${query.type === type ? ' class="selected" aria-current="page"' : ''}>${name}</a>`).join('')}</nav><div class="section-heading"><span>共 ${data.total} 份公开报告</span><span>每页 ${data.pageSize} 份</span></div><div class="report-grid">${data.items.map(report => `<article class="report-card"><span class="badge green">已审核发布</span><span class="report-date">${date(report.publishedAt)}</span><h2><a href="${escape(report.href)}" data-route>${escape(report.title)}</a></h2><p>正文 v${report.bodyRevision} · ${report.coverage.state === 'partial' ? '覆盖不完整' : report.coverage.state === 'no_input' ? '此周期无可用输入' : '输入覆盖完整'}</p>${report.corrected ? '<p>判定已更正，请核对当前报告。</p>' : ''}<a class="text-link" href="${escape(report.href)}" data-route>阅读报告 →</a></article>`).join('') || `<section class="empty" role="status"><h2>暂无报告</h2><p>${data.total ? '这一页已无报告，请返回第一页查看。' : '该周期还没有可公开阅读的报告。'}</p>${data.total ? `<a class="button" href="${escape(pageLink(url,query,1))}" data-route>返回第一页</a>` : ''}</section>`}</div><nav class="pagination" aria-label="报告分页">${data.page > 1 ? `<a class="button" href="${escape(pageLink(url,query,data.page - 1))}" data-route>上一页</a>` : ''}<span>第 ${data.page} 页 · 共 ${pages} 页</span>${data.page < pages ? `<a class="button" href="${escape(pageLink(url,query,data.page + 1))}" data-route>下一页</a>` : ''}</nav>`;
}
export function mountReportList(root, { scope, api, url }) {
  let disposed = false, query; const status = createStatusView(root);
  function dispose() { if (disposed) return; disposed = true; scope.dispose(); status.dispose(); root.innerHTML = ''; }
  scope.own(dispose);
  try { query = reportListQuery(url); } catch { status.show('error', { message: '筛选条件无效，请通过周报或月报入口重新选择。' }); return dispose; }
  function load() {
    if (disposed || !scope.active) return; status.show('loading');
    scope.run('report-list', signal => api.list({ ...query, signal }), data => {
      if (disposed) return; status.show('ready'); root.innerHTML = reportListMarkup(data,url,query);
    }, error => {
      if (error.status === 401) status.show('unauthorized');
      else if (error.status === 403) status.show('error', { message: '无权查看此列表。' });
      else if (error.status === 404) status.show('empty', { message: '暂无公开报告。' });
      else if (error.status === 410) status.show('withdrawn');
      else status.show('error', { retry: load });
    });
  }
  load(); return dispose;
}
