import {createJobResultActionsApi} from './api/job-result-actions.js';
import {createJobResultComparisonApi} from './api/job-result-comparison.js';
import {createGenerationIntentsApi} from './api/generation-intents.js';
import { createApiClient } from './api/client.js';
import { createHomeApi } from './api/home.js';
import { createIdentityApi } from './api/identity.js';
import { createReportsApi } from './api/reports.js';
import { createLearningReferenceApi } from './api/learning-reference.js';
import { createArticlesApi } from './api/articles.js';
import { createRecordsApi } from './api/records.js';
import { createArticleRecordsApi } from './api/article-records.js';
import { createInterpretationsApi } from './api/interpretations.js';
import {createOperationAffectedApi} from './api/operation-affected.js';
import { createAdminContentApi } from './api/admin-content.js';
import { createSessionState } from './state/session.js';
import { createPageRouter } from './router/lifecycle.js';
import { mountShell } from './components/layout/shell.js';
import { createStatusView } from './components/common/status-view.js';
import { mountHome } from './pages/home/index.js';
import { mountLogin } from './pages/auth/index.js';
import { mountReport } from './pages/reports/detail.js';
import { mountReportList } from './pages/reports/list.js';
import { createLeaveDialog } from './components/common/leave-dialog.js';
import { mountArticles } from './pages/articles/index.js';
import { mountArticleLearning } from './pages/articles/learning.js';
import { mountCatalog } from './pages/catalog/index.js';
import { mountInterpretation } from './pages/catalog/detail.js';
import { mountAdminCatalog } from './pages/admin/content-list.js';

export function createApplication({ root, browser, client = createApiClient(), notice = null, confirmDiscard = () => false }) {
  const session = createSessionState(client), home = createHomeApi(client), identityApi = createIdentityApi(client);
  const reports = createReportsApi(client), learningReference = createLearningReferenceApi(client), interpretations = createInterpretationsApi(client);
  const adminContent = createAdminContentApi(client);
  const generationIntents = createGenerationIntentsApi(client);
  const resultComparisons = createJobResultComparisonApi(client), resultActions = createJobResultActionsApi(client);
  const operationAffected = createOperationAffectedApi(client);
  const articles = createArticlesApi(client), records = createRecordsApi(client), articleRecords = createArticleRecordsApi(client);
  const leaveDialog = notice ? createLeaveDialog(notice) : null;
  const unsubscribeDialog = session.subscribe(() => leaveDialog?.clear());
  let logoutPending = false, stopped = false;
  function notify(message, retry = null) {
    if (!notice || stopped) return;
    notice.replaceChildren();
    notice.textContent = message;
    if (retry) {
      const button = notice.ownerDocument.createElement('button'); button.type = 'button'; button.textContent = '重试退出';
      button.addEventListener('click', retry, { once: true }); notice.append(button);
    }
  }
  async function logout(retry = false, confirmed = false) {
    if (logoutPending || stopped) return;
    if (!confirmed && !router.canLeave()) { const generation=session.snapshot.generation;leaveDialog?.request(()=>{if(!stopped&&session.snapshot.generation===generation)logout(retry,true);});return; }
    logoutPending = true; notify('正在退出…');
    try {
      // A retry refreshes the transport token without restoring identity in the view.
      if (retry) await client.getSession();
      await client.endSession();
      if (stopped) return;
      notify('已退出登录。'); await session.refresh(); router.navigate('/');
    } catch {
      notify('退出尚未确认。私人内容已清空，请重试。', () => logout(true));
    } finally { logoutPending = false; }
  }
  function navigate(path) { if (router.navigate(path)) root.querySelector('#main')?.focus({ preventScroll: true }); }
  const router = createPageRouter({ browser, session, mount({ route, url, scope, session: identity }) {
    const main = mountShell(root, { pathname: url.pathname, user: identity.user, scope, navigate, logout: () => logout() });
    if (route?.id === 'UI-01') return mountHome(main, { scope, api: home });
    if (route?.id === 'UI-02') return mountLogin(main, { scope, api: identityApi, identity, session, next: url.searchParams.get('next'), navigate });
    if (route?.id === 'UI-03') {
      let identifier; try { identifier = decodeURIComponent(url.pathname.split('/')[2]); } catch { identifier = ''; }
      return mountReport(main, { scope, api: reports, learning: learningReference, identifier, draft: url.searchParams.get('draft') === '1', identity, session, hash: url.hash });
    }
    if (route?.id === 'UI-04') return mountReportList(main, { scope, api: reports, url });
    if (route?.id === 'UI-05') {
      let identifier; try { identifier = decodeURIComponent(url.pathname.split('/')[2]); } catch { identifier = ''; }
      return mountReport(main, { scope, api: reports, learning: learningReference, identifier, expectedType: url.pathname.startsWith('/weekly-reports/') ? 'weekly' : 'monthly', draft: url.searchParams.get('draft') === '1', identity, session, hash: url.hash });
    }
    if (route?.id === 'UI-06') return mountArticles(main, { scope, api: articles, records, url, identity, session, navigate, confirmDiscard });
    if (route?.id === 'UI-07') return mountArticleLearning(main, { scope, api: articleRecords, articleApi: articles, records, url, identity, session, navigate, confirmDiscard });
    if (route?.id === 'UI-08') return mountCatalog(main, { scope, api: interpretations, url, navigate });
    if (route?.id === 'UI-09') return mountInterpretation(main, { scope, api: interpretations, url });
    if (route?.id === 'UI-10') return mountAdminCatalog(main, { scope, api: adminContent, impactApi: operationAffected, intentApi: generationIntents, comparisonApi: resultComparisons, resultActionsApi: resultActions, url, identity, session, navigate, confirmDiscard });
    const status = createStatusView(main); scope.own(() => status.dispose());
    if (!route) { status.show('empty', { message: '找不到此页面，请检查访问地址。' }); return; }
    const privatePage = url.pathname.startsWith('/app/') || url.pathname.startsWith('/admin/');
    if (privatePage && identity.phase === 'loading') status.show('loading');
    else if (privatePage && identity.phase === 'error') status.show('error', { message: '身份服务暂时不可用，请重试。', retry: () => session.refresh() });
    else if (privatePage && !identity.user) status.show('unauthorized');
    else if (url.pathname.startsWith('/admin/') && identity.user?.role !== 'owner') status.show('error', { message: '无权访问此页面。' });
    else status.show('error', { message: '此页面正在接入服务，请稍后再试。' });
  }, onLeaveBlocked: action => leaveDialog?.request(() => { action(); root.querySelector('#main')?.focus({ preventScroll: true }); }), onError: () => notify('页面暂时无法显示，请刷新重试。') });
  router.start(); session.refresh();
  return Object.freeze({ session, router, dispose() {
    if (stopped) return; stopped = true; leaveDialog?.dispose();unsubscribeDialog();router.dispose(); session.dispose(); notice?.replaceChildren();
  } });
}

if (typeof document !== 'undefined') {
  const options = { root: document.getElementById('app'), notice: document.getElementById('notification'), browser: { events: window, location: window.location, history: window.history } };
  let application = createApplication(options);
  window.addEventListener('pagehide', () => application.dispose());
  // A document restored from the browser cache must recheck identity rather than reuse old views.
  window.addEventListener('pageshow', event => { if (event.persisted) application = createApplication(options); });
}
