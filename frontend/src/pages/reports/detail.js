import { createStatusView } from '../../components/common/status-view.js';

const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const time = value => value === null ? '未知' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
const publication = { draft: '私人草稿', approved: '已审核，尚未发布', published: '已审核发布', withdrawn: '已撤回' };
const kind = { daily: '日报', weekly: '周报', monthly: '月报' };
export function taskMarkup(task, reference = { state: 'checking', href: null }) {
  if (!task) return '';
  return `<section class="practice-card"><div class="practice-label">学习任务</div><h2>${escape(task.title)}</h2><p>${escape(task.reason)}</p>${reference.state === 'available' ? `<a class="button primary full" href="${escape(reference.href)}" data-route>开始学习</a>${reference.routeTitle ? `<small>所在路线：${escape(reference.routeTitle)}</small>` : ''}` : reference.state === 'unavailable' ? '<p role="status">此学习任务已不可用，入口已隐藏。</p>' : reference.state === 'unknown' ? '<p role="status">暂时无法确认学习入口。</p><button type="button" class="button" data-learning-retry>重试核对</button>' : '<p role="status">正在核对学习入口…</p>'}</section>`;
}
export function reportMarkup(report) {
  const coverage = report.coverage;
  const back = report.type === 'daily' ? '/' : `/${report.type}-reports`;
  return `<a class="back-link" href="${back}" data-route>${report.type === 'daily' ? '返回首页' : '返回报告列表'}</a><div class="detail-layout split-detail"><article class="detail-body" tabindex="0" aria-label="报告正文"><div class="detail-kicker"><span class="badge ${report.publicationState === 'published' ? 'green' : 'orange'}">${publication[report.publicationState]}</span><span>${kind[report.type]} · 正文 v${report.bodyRevision} · ${report.publishedAt === null ? '发布时间未知' : time(report.publishedAt)}</span></div><h1>${escape(report.bodyTitle)}</h1><p class="lead">AI 辅助整理，站长审核${report.publicationState === 'published' ? '发布' : ''}。本报告按固定输入整理，后续来源变化不会自动改写本稿。</p><div class="coverage"><strong>实际覆盖：${time(report.periodStart)} — ${time(report.periodEnd)}（北京时间，截止不含）</strong><span>输入截止：${time(report.inputCutoffAt)}</span>${coverage.state === 'partial' ? '<span class="warning-text">覆盖不完整，请结合缺口理解结论。</span>' : coverage.state === 'no_input' ? '<span>此周期无可用输入。</span>' : '<span>输入覆盖完整。</span>'}${coverage.missingDates.length ? `<span>缺失日期：${coverage.missingDates.map(escape).join('、')}</span>` : ''}${coverage.noUpdateDates.length ? `<span>已核实无新增日期：${coverage.noUpdateDates.map(escape).join('、')}</span>` : ''}${coverage.missingInputs.map(item => `<span>${escape(item.kind)}${item.id ? ` · ${escape(item.id)}` : ''}${item.businessDate ? ` · ${escape(item.businessDate)}` : ''}：${escape(item.reason)}</span>`).join('')}</div>${report.corrected ? '<div class="notice">判定已更正，请核对当前说明与覆盖情况。</div>' : ''}${report.reviewRequired ? '<div class="notice">此稿需要重新审核。</div>' : ''}${report.blockingReasons.map(reason => `<p class="limitation">${escape(reason)}</p>`).join('')}${report.sections.length ? report.sections.map((section, index) => `<section class="analysis-section" id="report-section-${index + 1}"><h2 tabindex="-1">${escape(section.heading)}</h2><p class="report-text">${escape(section.text)}</p>${section.citationIds.length ? `<p class="report-citations">引用：${section.citationIds.map(key => escape(report.citations.find(item => item.id === key)?.title ?? key)).join('、')}</p>` : ''}</section>`).join('') : '<p>此报告暂无正文。</p>'}${report.citations.length ? `<section class="analysis-section"><h2>引用与依据</h2><ul>${report.citations.map(item => `<li>${item.sourceUrl ? `<a class="external" href="${escape(item.sourceUrl)}" target="_blank" rel="noopener noreferrer">${escape(item.title)}</a>` : escape(item.title)}${item.revision === null ? '' : `<small> · 引用版本 ${item.revision}</small>`}</li>`).join('')}</ul></section>` : ''}<details class="report-input"><summary>输入依据</summary><p>输入快照：${escape(report.inputSnapshotId ?? '未知')}；生成代次：${report.generation ?? '未知'}。</p></details></article><aside class="right-rail" tabindex="0" aria-label="练习与本期目录"><div id="learning-reference">${taskMarkup(report.learningTask)}</div><div class="rail-section"><h3>本期内容</h3>${report.sections.map((section, index) => `<a class="toc" href="#report-section-${index + 1}">${index + 1}. ${escape(section.heading)}</a>`).join('') || '<p>暂无章节。</p>'}</div></aside></div>`;
}

export function mountReport(root, { scope, api, learning, identifier, draft = false, identity, session, hash = '', expectedType = null }) {
  const status = createStatusView(root), workspace = root.closest?.('.workspace'); let disposed = false, report = null;
  function dispose() { if (disposed) return; disposed = true; report = null; scope.dispose(); status.dispose(); root.innerHTML = ''; workspace?.classList.remove('split-workspace'); }
  scope.own(dispose);
  function verify() {
    if (!report?.learningTask || disposed || !scope.active) return;
    const task = report.learningTask;
    const target = root.querySelector('#learning-reference');
    if (target) target.innerHTML = taskMarkup(task);
    scope.run('learning-reference', signal => learning.lookup(task, { signal }), reference => { if (!disposed && target) target.innerHTML = taskMarkup(task, reference); }, () => { if (!disposed && target) target.innerHTML = taskMarkup(task, { state: 'unknown', href: null }); });
  }
  scope.listen(root, 'click', event => { if (event.target.closest('[data-learning-retry]')) verify(); });
  function load() {
    if (disposed || !scope.active) return;
    if (draft && identity.phase === 'loading') { status.show('loading'); return; }
    if (draft && identity.phase === 'error') { status.show('error', { message: '身份服务暂时不可用，请重试。', retry: () => session.refresh() }); return; }
    if (draft && !identity.user) { status.show('unauthorized'); return; }
    if (draft && identity.user.role !== 'owner') { status.show('error', { message: '无权访问此草稿。' }); return; }
    status.show('loading');
    scope.run('report', signal => api.detail(identifier, { signal, draft }), data => {
      if (expectedType !== null && data.type !== expectedType) { status.show('empty', { message: '此周期报告不存在，请返回报告列表。' }); return; }
      if (disposed) return; report = data; status.show('ready'); workspace?.classList.add('split-workspace'); root.innerHTML = reportMarkup(data); verify();
      if (/^#report-section-\d+$/.test(hash)) { const target = root.querySelector(hash); target?.scrollIntoView({ block: 'start' }); target?.querySelector('h2')?.focus({ preventScroll: true }); }
    }, error => {
      if (error.status === 401) status.show('unauthorized');
      else if (error.status === 403) status.show('error', { message: '无权查看此报告。' });
      else if (error.status === 404) status.show('empty', { message: '报告不存在或尚未公开，请返回首页。' });
      else if (error.status === 410) status.show('withdrawn');
      else status.show('error', { retry: load });
    });
  }
  load(); return dispose;
}
