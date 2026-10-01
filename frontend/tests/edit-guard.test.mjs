import test from 'node:test';
import assert from 'node:assert/strict';
import { createPageRouter } from '../src/router/lifecycle.js';
test('blocked navigation offers one resumable operation and confirmed navigation follows its original target',()=>{
  let href='https://example.invalid/app/articles',resume;
  const session={snapshot:{generation:0},subscribe:()=>()=>{}},browser={events:new EventTarget(),location:{get href(){return href;}},history:{pushState(_s,_t,u){href=String(u);}}};
  const router=createPageRouter({browser,session,onLeaveBlocked:action=>resume=action,mount:({scope})=>{scope.protectEdits(()=>true,()=>false);}});
  router.start();router.navigate('/monthly-reports?page=2');assert.equal(href,'https://example.invalid/app/articles');assert.equal(typeof resume,'function');resume();assert.equal(href,'https://example.invalid/monthly-reports?page=2');router.dispose();
});
test('unsaved edits cancel navigation/history departure and refresh, then confirmed departure disposes once',()=>{
 let href='https://example.invalid/app/articles/fictional-article',allow=false,dirty=true,currentScope,disposed=0;
 const listeners=new Set(),session={snapshot:{generation:0},subscribe:fn=>{listeners.add(fn);return()=>listeners.delete(fn);}};
 const events=new EventTarget(),history={pushState(_s,_t,url){href=String(url);},replaceState(_s,_t,url){href=String(url);}};
 const router=createPageRouter({browser:{events,history,location:{get href(){return href;}}},session,mount:({scope})=>{currentScope=scope;scope.protectEdits(()=>dirty,()=>allow);return()=>disposed++;}});
 router.start();assert.equal(router.canLeave(),false);assert.equal(router.navigate('/'),false);assert.match(href,/fictional-article$/);assert.equal(disposed,0);
 href='https://example.invalid/login';events.dispatchEvent(new Event('popstate'));assert.match(href,/fictional-article$/);assert.equal(disposed,0);
 const unload=new Event('beforeunload',{cancelable:true});events.dispatchEvent(unload);assert.equal(unload.defaultPrevented,true);
 allow=true;assert.equal(router.navigate('/'),true);assert.equal(disposed,1);assert.equal(href,'https://example.invalid/');assert.equal(currentScope.signal.aborted,false);
 router.dispose();assert.equal(disposed,2);const after=new Event('beforeunload',{cancelable:true});events.dispatchEvent(after);assert.equal(after.defaultPrevented,false);
});
test('forced session invalidation disposes guarded private edits without asking to keep them',()=>{
 const listeners=new Set();const session={snapshot:{generation:0},subscribe:fn=>{listeners.add(fn);return()=>listeners.delete(fn);}};let prompts=0,disposed=0;
 const browser={events:new EventTarget(),location:{href:'https://example.invalid/app/articles'},history:{pushState(){},replaceState(){}}};
 const router=createPageRouter({browser,session,mount:({scope})=>{scope.protectEdits(()=>true,()=>{prompts++;return false;});return()=>disposed++;}});router.start();session.snapshot={generation:1};for(const fn of [...listeners])fn();assert.equal(prompts,0);assert.equal(disposed,1);router.dispose();
});
