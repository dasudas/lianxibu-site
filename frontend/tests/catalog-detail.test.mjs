import test from 'node:test';
import assert from 'node:assert/strict';
import {interpretationMarkup,mountInterpretation} from '../src/pages/catalog/detail.js';
import {createPageScope} from '../src/state/page-scope.js';
const content={kind:'workflow',id:'fictional-one',name:'Fictional tool',bodyTitle:'Fictional <body>',provider:null,tags:['Fictional tag'],description:null,evidenceTime:null,publishedAt:null,bodyRevision:1,inputCutoffAt:null,corrected:false,coverage:{state:'partial',missingInputs:[],missingDates:['2026-01-01'],noUpdateDates:[]},stars:null,starsGrowth:0,sections:[{heading:'Fictional heading',text:'Fictional <text>',citationIds:['fictional-cite']}],citations:[{id:'fictional-cite',title:'Fictional source',sourceUrl:'https://example.invalid/doc',revision:null}],metrics:[{benchmark:'Fictional benchmark',benchmarkVersion:'Fictional v1',task:'Fictional task',score:null,unit:'points',evaluatedAt:null,higherIsBetter:true,evaluationScope:'configuration',provenance:'author_self_report'}]};
test('detail preserves split reader, exact current evidence and metric dimensions without invented scores or history',()=>{
 const html=interpretationMarkup(content);assert.match(html,/split-detail/);assert.match(html,/Fictional &lt;body&gt;/);assert.match(html,/Fictional &lt;text&gt;/);assert.match(html,/Stars 未知/);assert.match(html,/增量 0/);assert.match(html,/Fictional benchmark/);assert.match(html,/Fictional v1/);assert.match(html,/作者自报/);assert.match(html,/特定配置/);assert.match(html,/资料时间未知/);assert.match(html,/2026-01-01/);assert.match(html,/noopener noreferrer/);assert.doesNotMatch(html,/历史修订 v1|能力排名|7 天/);
});
class Element extends EventTarget{constructor(doc){super();this.ownerDocument=doc;this.children=[];this.innerHTML='';}setAttribute(){}append(...v){this.children.push(...v);}replaceChildren(...v){this.children=v;this.innerHTML='';}querySelector(){return null;}}
function setup(path='/workflows/fictional-one'){const session={snapshot:{generation:0},subscribe:()=>()=>{}};const doc={createElement:()=>new Element(doc)};return {root:new Element(doc),scope:createPageScope(session),url:new URL('https://example.invalid'+path)};}
const tick=()=>new Promise(r=>setImmediate(r));
test('legacy revision explains unavailable historical body without transport or current substitution, invalid identity is refused',()=>{
 for(const path of ['/models/fictional-one/revisions/1','/agents/fictional-one/revisions/2']){const f=setup(path);let calls=0;mountInterpretation(f.root,{...f,api:{detail:()=>calls++}});assert.equal(calls,0);assert.match(f.root.innerHTML,/历史正文不可用/);assert.doesNotMatch(f.root.innerHTML,/Fictional &lt;body&gt;/);f.scope.dispose();}
 const f=setup('/models/%E0%A4%A');let calls=0;mountInterpretation(f.root,{...f,api:{detail:()=>calls++}});assert.equal(calls,0);f.scope.dispose();
});
test('reader provides error retry and disposed detail never restores late content',async()=>{
 const f=setup();let resolve,options;const dispose=mountInterpretation(f.root,{...f,api:{detail:(id,opts)=>{options=opts;return new Promise(r=>resolve=r);}}});assert.equal(options.kind,'workflow');dispose();resolve(content);await tick();assert.equal(f.root.innerHTML,'');assert.equal(f.scope.signal.aborted,true);
 const retry=setup();let calls=0;mountInterpretation(retry.root,{...retry,api:{detail:async()=>{if(++calls===1)throw {status:503};return content;}}});await tick();assert.match(retry.root.children[0].textContent,/暂时无法加载/);retry.root.children[1].dispatchEvent(new Event('click'));await tick();assert.match(retry.root.innerHTML,/Fictional &lt;body&gt;/);retry.scope.dispose();
});
