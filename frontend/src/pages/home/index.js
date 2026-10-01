import { createStatusView } from '../../components/common/status-view.js';

const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const link = (title, href, className = '') => `<a class="${className}" href="${escape(href)}" data-route>${escape(title)}</a>`;
const date = value => value === null ? '发布时间未知' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'medium' }).format(new Date(value));
const preview = '<svg viewBox="0 0 420 270" fill="none"><g stroke="currentColor" stroke-width="1.5"><path d="M55 135H125M125 135C165 135 155 55 195 55H235M125 135H235M125 135C165 135 155 215 195 215H235M305 55H365M305 135H365M305 215H365"/><path d="M270 77V113M270 157V193" stroke-dasharray="4 6"/></g><g fill="#173e35" stroke="#a9c3b1"><circle cx="55" cy="135" r="23"/><circle cx="125" cy="135" r="6"/><rect x="225" y="33" width="90" height="44" rx="10"/><rect x="225" y="113" width="90" height="44" rx="10"/><rect x="225" y="193" width="90" height="44" rx="10"/></g><g fill="#d8eabd"><circle cx="365" cy="55" r="5"/><circle cx="365" cy="135" r="5"/><circle cx="365" cy="215" r="5"/></g><g fill="#f0f5e8" font-size="16" text-anchor="middle"><text x="55" y="141">基础</text><text x="270" y="61">前端</text><text x="270" y="141">Java</text><text x="270" y="221">Python</text></g><g fill="#c9dbd0" font-size="13" text-anchor="middle"><text x="365" y="83">实践</text><text x="365" y="163">实践</text><text x="365" y="243">实践</text></g></svg>';

export function homeMarkup(data) {
  const selected = data.packages.find(item => item.id === data.defaultPackageId);
  const packages = data.packages.length ? `<section class="practice-card"><div class="practice-label">学习路线</div><h2>从一个知识点开始</h2>${data.packages.map(item => `<div><h3>${link(item.title, item.href)}</h3><p>${escape(item.description)}</p></div>`).join('')}</section>` : '<section class="practice-card"><h2>暂无已发布的学习内容</h2><p>内容发布后，可从这里进入学习路线。</p></section>';
  const reports = data.reports.length ? data.reports.map(item => `<article class="news-card"><span class="source-mark green" aria-hidden="true">报</span><div class="news-content"><div class="news-meta"><span>已发布报告</span><span>${date(item.publishedAt)}</span>${item.coverage === 'partial' ? '<span class="badge orange">覆盖不完整</span>' : ''}${item.coverage === 'no_input' ? '<span class="badge">无输入</span>' : ''}</div><h3>${link(item.title, item.href)}</h3>${item.corrected ? '<p>判定已更正，请查看当前说明。</p>' : ''}<div class="news-bottom"><span>站长审核发布</span>${link('阅读解读', item.href, 'text-link')}</div></div></article>`).join('') : '<div class="empty"><h2>还没有已发布的报告</h2><p>审核发布后，最新报告会出现在这里。</p></div>';
  return `<div class="page-heading"><div><h1>开发知识图谱</h1><p>梳理知识脉络，找到适合自己的开发学习路径。</p></div></div><section class="digest-hero knowledge-entry" aria-labelledby="knowledge-intro"><div class="hero-copy"><h2 id="knowledge-intro">把知识连起来，让学习有方向</h2><p>沿着知识点之间的关联，理清基础、进阶与实践的学习顺序。</p><p>选择一条路线，查看学习目标、前置知识与相关资料，找到下一步要学什么。</p><div class="hero-bottom">${link(selected ? '进入开发知识图谱' : '选择学习路线', selected?.href ?? '/roadmaps', 'button light')}</div></div><div class="knowledge-preview" aria-hidden="true">${preview}</div></section><div class="home-grid"><section><div class="section-heading"><h2>最新发布的报告</h2><span>精选解读 · 来源可追溯</span></div><div class="news-list">${reports}</div></section><aside class="right-rail">${packages}<section class="rail-section"><div class="section-heading"><h2>探索技术解读</h2></div>${[['模型解读', '理解能力，也理解边界', '/models'], ['Agent 与 Skill', '从工具到可复用的工作方式', '/agents'], ['AI 开发方案', '让需求、实现与验证连起来', '/workflows']].map(([title, detail, href]) => `<a class="explore-item" href="${href}" data-route><span><strong>${title}</strong><small>${detail}</small></span></a>`).join('')}</section><div class="reading-note"><div><strong>保持好奇，也保持判断</strong><p>AI 辅助整理，站长审核发布。<br>事实、观点和建议分别呈现。</p></div></div></aside></div>`;
}

export function mountHome(root, { scope, api }) {
  const status = createStatusView(root); let disposed = false;
  function dispose() { if (disposed) return; disposed = true; status.dispose(); root.innerHTML = ''; }
  scope.own(dispose);
  function load() {
    if (!scope.active || disposed) return;
    status.show('loading');
    scope.run('home', signal => api.load({ signal }), data => { status.show('ready'); root.innerHTML = homeMarkup(data); }, error => {
      if (error.status === 401) status.show('unauthorized');
      else if (error.status === 410) status.show('withdrawn');
      else if (error.status === 403) status.show('error', { message: '无权访问此页面。' });
      else if (error.status === 404) status.show('empty', { message: '找不到此页面。' });
      else status.show('error', { retry: load });
    });
  }
  load(); return dispose;
}
