import test from 'node:test';
import assert from 'node:assert/strict';
import {catalogQuery,catalogMarkup,catalogView,mountCatalog} from '../src/pages/catalog/index.js';
import {createPageScope} from '../src/state/page-scope.js';
const item={id:'fictional-one',kind:'workflow',name:'Fictional <tool>',title:'Fictional title',provider:null,tags:['Fictional tag'],description:null,evidenceTime:null,publishedAt:null,stars:null,starsGrowth:null,bodyRevision:1,href:'/workflows/fictional-one'};
test('catalog preserves grid and honestly distinguishes local filters and service totals, unknown values and no model ranking',()=>{
    const query=catalogQuery(new URL('https://example.invalid/workflows?view_q=Fictional&view_tag=Fictional%20tag&view_sort=growth&page=2'));
    const rows=[item,{...item,id:'fictional-two',name:'Fictional second',stars:0,starsGrowth:0},{...item,id:'fictional-three',name:'Fictional third',stars:10,starsGrowth:2}];
    assert.deepEqual(catalogView(rows,query).map(x=>x.id),['fictional-three','fictional-two','fictional-one']);
    const html=catalogMarkup({items:rows,total:23,page:2,pageSize:20},query);assert.match(html,/catalog-grid/);assert.match(html,/本页/);assert.match(html,/全部 23/);assert.match(html,/Stars 未知/);assert.match(html,/Stars 0/);assert.match(html,/Fictional &lt;tool&gt;/);assert.match(html,/资料时间未知/);assert.doesNotMatch(html,/演示资料|排名|2026-09-14/);
    for(const search of ['?page=0','?view_q=a&view_q=b','?view_sort=rank','?view_provider='+ 'x'.repeat(257)])assert.throws(()=>catalogQuery(new URL('https://example.invalid/models'+search)));
});
class Element extends EventTarget{constructor(doc){super();this.ownerDocument=doc;this.children=[];this.innerHTML='';}setAttribute(){}append(...v){this.children.push(...v);}replaceChildren(...v){this.children=v;this.innerHTML='';}querySelector(){return null;}}
const tick=()=>new Promise(r=>setImmediate(r));
function setup(search=''){const session={snapshot:{generation:0},subscribe:()=>()=>{}};const doc={createElement:()=>new Element(doc)};return {root:new Element(doc),scope:createPageScope(session),url:new URL('https://example.invalid/models'+search)};}
test('catalog sends only real kind/page filters, cancels late lists and refuses invalid URL before transport',async()=>{
    const f=setup('?view_q=Fictional&view_provider=Fictional&page=2');let options,resolve;const dispose=mountCatalog(f.root,{...f,api:{list:opts=>{options=opts;return new Promise(r=>resolve=r);}},navigate(){}});assert.equal(options.kind,'model');assert.equal(options.page,2);assert.equal(options.view_q,undefined);dispose();resolve({items:[],page:2,pageSize:20,total:0});await tick();assert.equal(f.root.innerHTML,'');assert.equal(f.scope.signal.aborted,true);
    const invalid=setup('?view_sort=stars');let calls=0;mountCatalog(invalid.root,{...invalid,api:{list:()=>calls++},navigate(){}});assert.equal(calls,0);invalid.scope.dispose();
});
test('service failure keeps explicit retry and a successful later read restores only the current page',async()=>{
    const f=setup();let calls=0;mountCatalog(f.root,{...f,api:{list:async()=>{if(++calls===1)throw {status:503};return {items:[{...item,kind:'model'}],page:1,pageSize:20,total:1};}},navigate(){}});await tick();assert.match(f.root.children[0].textContent,/暂时无法加载/);f.root.children[1].dispatchEvent(new Event('click'));await tick();assert.match(f.root.innerHTML,/catalog-grid/);f.scope.dispose();
});
