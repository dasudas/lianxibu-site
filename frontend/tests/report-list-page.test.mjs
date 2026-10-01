import test from 'node:test';
import assert from 'node:assert/strict';
import { mountReportList, reportListMarkup, reportListQuery } from '../src/pages/reports/list.js';
import { createPageScope } from '../src/state/page-scope.js';
test('report list retains tabs/cards and encodes page/type in shareable URLs without invented quantities', () => {
  const url=new URL('https://example.invalid/weekly-reports?type=daily&page=2'); const query=reportListQuery(url); assert.deepEqual(query,{type:'daily',page:2});
  const markup=reportListMarkup({items:[{title:'<script>fictional</script>',href:'/digests/fictional-report',bodyRevision:2,publishedAt:null,coverage:{state:'partial'},corrected:true}],total:21,page:2,pageSize:20},url,query);
  assert.match(markup,/report-grid/);assert.match(markup,/report-card/);assert.match(markup,/type=daily/);assert.match(markup,/page=1/);assert.match(markup,/发布时间未知/);assert.match(markup,/正文 v2/);assert.match(markup,/覆盖不完整/);assert.doesNotMatch(markup,/<script>|私人草稿|输入数量/);
  for(const search of ['?page=0','?page=1.2','?type=bad','?type=daily&type=daily','?page=1&page=2']) assert.throws(()=>reportListQuery(new URL('https://example.invalid/weekly-reports'+search)));
});
class Element{constructor(doc){this.events=new EventTarget();this.ownerDocument=doc;this.children=[];this.attrs={};this.innerHTML='';} addEventListener(...v){this.events.addEventListener(...v);} removeEventListener(...v){this.events.removeEventListener(...v);} setAttribute(k,v){this.attrs[k]=v;}append(...v){this.children.push(...v);} replaceChildren(...v){this.children=v;this.innerHTML='';}}
function fixture(){const session={snapshot:{generation:0},subscribe:()=>()=>{}};const scope=createPageScope(session);const doc={createElement:()=>new Element(doc)};return{scope,root:new Element(doc)};}
const tick=()=>new Promise(r=>setImmediate(r));
test('report list loads actual page, disposes cancellation and refuses stale response',async()=>{
  const f=fixture();let finish,signal;const dispose=mountReportList(f.root,{scope:f.scope,url:new URL('https://example.invalid/monthly-reports?page=2'),api:{list:args=>{signal=args.signal;assert.equal(args.type,'monthly');return new Promise(r=>finish=r);}}});
  dispose();assert.equal(signal.aborted,true);finish({items:[],page:2,pageSize:20,total:0});await tick();assert.equal(f.root.innerHTML,'');
});
test('empty/out of range, service failure and invalid URL keep explicit recovery',async()=>{
  for(const [response,text] of [[{items:[],page:1,pageSize:20,total:0},/暂无报告/],[{items:[],page:9,pageSize:20,total:1},/返回第一页/]]){const f=fixture();mountReportList(f.root,{scope:f.scope,url:new URL('https://example.invalid/weekly-reports?page='+response.page),api:{list:async()=>response}});await tick();assert.match(f.root.innerHTML,text);f.scope.dispose();}
  for(const [status,text] of [[401,/登录/],[403,/无权/],[404,/暂无/],[410,/撤回/],[503,/重试/]]){const f=fixture();mountReportList(f.root,{scope:f.scope,url:new URL('https://example.invalid/weekly-reports'),api:{list:async()=>{throw{status};}}});await tick();assert.match(f.root.children[0].textContent,text);f.scope.dispose();}
  const f=fixture();let calls=0;mountReportList(f.root,{scope:f.scope,url:new URL('https://example.invalid/weekly-reports?page=0'),api:{list:async()=>{calls++;}}});assert.equal(calls,0);assert.match(f.root.children[0].textContent,/筛选/);f.scope.dispose();
});
