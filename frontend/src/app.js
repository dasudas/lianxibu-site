import { resolveRoute } from './router/routes.js';

const root = document.getElementById('app');
const route = resolveRoute(window.location.pathname);
const title = document.createElement('h1');
const message = document.createElement('p');

if (route) {
  root.dataset.page = route.id;
  title.textContent = '页面暂不可用';
  message.textContent = '此页面正在接入服务，请稍后再试。';
} else {
  root.dataset.page = 'not-found';
  title.textContent = '找不到此页面';
  message.textContent = '请检查访问地址。';
}

root.append(title, message);
