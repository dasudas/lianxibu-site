import {escape} from '../learning/shared.js';
export function adHocQuestionMarkup(state,href){
 const target=state?.selected;if(target?.taskType!=='ad_hoc_ai'||!['generation_intent','logical_task'].includes(target.recordKind)||!target.intentId||state.phase==='forbidden')return '';
 const phase=state.questionPhase??'idle',view=state.question;
 let body=phase==='loading'?'<p role="status">正在读取原问题与保留回答…</p>':phase==='error'?`<p role="alert">${escape(state.questionMessage)}</p>`:'<p>仅站长可见。查看不会发送 AI 请求。</p>';
 if(phase==='ready'&&view){
  body=`<h3>原始问题</h3><div class="report-text">${escape(view.originalRequest.prompt)}</div>`;
  const root=view.preparedJob;
  body+=root?`<p>原任务已调用 ${root.callCount}/3 次，剩余 ${root.remainingAttempts} 次。</p><a class="button" data-route href="${escape(href(root.id))}">查看原执行任务</a>`:'<p>原问题已登记，执行任务尚未准备。</p>';
  const result=view.retainedResult;
  body+=result?`<h3>保留回答</h3><article><h4>${escape(result.payload.title)}</h4>${result.payload.sections.map(s=>`<section><h5>${escape(s.heading)}</h5><div class="report-text">${escape(s.text)}</div></section>`).join('')}</article>`:'<p role="status">暂无可核验的保留回答。</p>';
 }else if(state.questionMessage&&phase!=='error')body+=`<p role="status">${escape(state.questionMessage)}</p>`;
 return `<section class="panel" id="job-question-panel" aria-label="原问题与保留回答" aria-busy="${phase==='loading'}"><h2>原问题与保留回答</h2>${body}<div class="actions"><button class="button" type="button" data-jobs-question${phase==='loading'?' disabled':''}>${phase==='ready'?'刷新原问题与保留回答':'查看原问题与保留回答'}</button><button class="button" type="button" data-jobs-question-clear>清空问答显示</button></div></section>`;
}
