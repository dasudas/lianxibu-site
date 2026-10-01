import test from 'node:test';
import assert from 'node:assert/strict';
import {learningQuery,learningMarkup,mountArticleLearning} from '../src/pages/articles/learning.js';
import {createPageScope} from '../src/state/page-scope.js';
const record={id:'fictional-article',kind:'article',packageId:null,revision:1,status:'planned',selfAssessment:'unassessed',note:'<script>Fictional note</script>',artifactUrl:null,isRead:false,isFavorite:true,isIgnored:false};
const data={items:[{record,article:{id:record.id,title:'Fictional title',sourceName:null,href:'/app/articles/fictional-article'}}],page:1,pageSize:20,total:1,stats:{total:32,favorite:5,planned:12,learning:10,completed:10}};
test('learning records retain full statistics, shareable service filters and escaped private notes with explicit missing metadata',()=>{
    const query=learningQuery(new URL('https://example.invalid/app/learning?tab=favorite&q=Fictional&page=2'));
    assert.equal(query.favorite,true);assert.equal(query.page,2);const markup=learningMarkup(data,query);assert.match(markup,/learning-stats/);assert.match(markup,/learning-list/);assert.match(markup,/5<small>/);assert.match(markup,/tab=planned/);assert.match(markup,/q=Fictional/);assert.match(markup,/&lt;script&gt;/);assert.doesNotMatch(markup,/<script>|开始今日练习/);
    const missing=learningMarkup({...data,items:[{record,article:null}]},query);assert.match(missing,/原资讯暂不可用/);assert.match(missing,/Fictional note/);assert.doesNotMatch(missing,/data-record-edit/);
    for(const search of ['?tab=invalid','?tab=all&tab=favorite','?page=0','?edit=%00','?q='+ 'x'.repeat(257)])assert.throws(()=>learningQuery(new URL('https://example.invalid/app/learning'+search)));
});
class Element extends EventTarget{constructor(doc){super();this.ownerDocument=doc;this.children=[];this.innerHTML='';this.panel=null;}setAttribute(){}append(...v){this.children.push(...v);}replaceChildren(...v){this.children=v;this.innerHTML='';}querySelector(selector){return selector==='#article-editor'?this.panel:null;}}
function setup(user={id:'fictional-user',role:'user'},search=''){const session={snapshot:{generation:0,phase:'ready',user},subscribe:()=>()=>{},refresh:async()=>{}};const doc={createElement:()=>new Element(doc)};const root=new Element(doc);root.panel=new Element(doc);return{session,identity:session.snapshot,scope:createPageScope(session),root,url:new URL('https://example.invalid/app/learning'+search)};}
const tick=()=>new Promise(r=>setImmediate(r));
test('anonymous and invalid filters never read; unmount cancels late private lists',async()=>{
    let calls=0;for(const f of [setup(null),setup(undefined,'?tab=invalid')]){mountArticleLearning(f.root,{...f,api:{list:()=>{calls++;}},articleApi:{},records:{},navigate(){}});f.scope.dispose();}assert.equal(calls,0);
    const f=setup();let resolve;const dispose=mountArticleLearning(f.root,{...f,api:{list:()=>new Promise(r=>resolve=r)},articleApi:{},records:{},navigate(){}});dispose();resolve(data);await tick();assert.equal(f.root.innerHTML,'');assert.equal(f.scope.signal.aborted,true);
});
test('editing the chosen current-page record uses its revision and protects note draft without trusting an arbitrary URL record',async()=>{
    const f=setup(undefined,'?edit=fictional-article');let command;
    mountArticleLearning(f.root,{...f,api:{list:async()=>data},articleApi:{},records:{save:async(identity,fields)=>{command={identity,fields};return {...record,note:fields.note,revision:2};}},navigate(){},makeKey:()=> 'fictional-save-key'});await tick();
    assert.match(f.root.panel.innerHTML,/私人笔记/);const input=new Event('input');Object.defineProperty(input,'target',{value:{matches:s=>s==='[data-record-field="note"]',value:'Fictional changed note'}});f.root.dispatchEvent(input);assert.equal(f.scope.hasUnsavedChanges,true);
    const submit=new Event('submit',{cancelable:true});Object.defineProperty(submit,'target',{value:{matches:s=>s==='[data-record-form]'}});f.root.dispatchEvent(submit);await tick();assert.equal(command.fields.expected_revision,1);assert.equal(command.fields.note,'Fictional changed note');assert.equal(command.identity.id,'fictional-article');assert.equal(f.scope.hasUnsavedChanges,false);f.scope.dispose();
    const unknown=setup(undefined,'?edit=fictional-other');mountArticleLearning(unknown.root,{...unknown,api:{list:async()=>data},articleApi:{detail:()=>assert.fail('arbitrary record must not be loaded')},records:{},navigate(){}});await tick();assert.match(unknown.root.panel.innerHTML,/不在当前结果/);unknown.scope.dispose();
});
