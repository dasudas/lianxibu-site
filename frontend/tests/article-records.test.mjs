import test from 'node:test';
import assert from 'node:assert/strict';
import {createArticleRecordsApi} from '../src/api/article-records.js';
const record={package_id:null,object_kind:'article',object_id:'fictional-article',revision:1,status:'planned',self_assessment:'unassessed',note:'Fictional note',artifact_url:null,latest_answer_text:null,latest_submission_url:null,confirmed_body_revision:null,updated_at:'2026-01-01T00:00:00.000000Z',is_read:false,is_favorite:true,is_ignored:false};
const page=()=>({items:[{article:{id:'fictional-article',title:'Fictional title',source_name:null,body_text:'Fictional forbidden extra'},record}],page:1,page_size:20,total:1,stats:{total:2,favorite:1,planned:1,learning:0,completed:1,internal_user_id:'fictional-other'}});
test('article record aggregate sends literal service filters and preserves independent full statistics without private extras',async()=>{
    let request;const api=createArticleRecordsApi({request:async(...args)=>{request=args;return {data:page()};}});
    const result=await api.list({q:'100%_',status:'planned',favorite:false});
    assert.equal(request[0],'GET');assert.equal(request[1],'/private/article-records');assert.deepEqual(request[2].query,{q:'100%_',status:'planned',favorite:false,page:1,page_size:20});
    assert.equal(result.stats.total,2);assert.equal(result.total,1);assert.equal(result.items[0].record.id,'fictional-article');assert.equal(result.items[0].article.sourceName,null);assert.equal(result.items[0].article.href,'/app/articles/fictional-article');assert.equal(result.items[0].article.body_text,undefined);assert.equal(result.stats.internal_user_id,undefined);assert.ok(Object.isFrozen(result.items[0]));
});
test('missing article metadata retains self record while malformed identity and aggregate statistics fail closed',async()=>{
    let data=page();const api=createArticleRecordsApi({request:async()=>({data})});data.items[0].article=null;assert.equal((await api.list()).items[0].record.note,'Fictional note');
    for(const mutate of [p=>p.items[0].article.id='fictional-other',p=>p.items[0].record={...record,object_kind:'question'},p=>p.stats.favorite=3,p=>p.stats.completed=2,p=>p.total=3,p=>p.items.push(p.items[0]),p=>p.stats.total=9007199254740992,p=>p.page=2]){data=page();mutate(data);await assert.rejects(api.list(),e=>e.code==='invalid_response');}
});
test('invalid article record filters never send a request',async()=>{let calls=0;const api=createArticleRecordsApi({request:async()=>{calls++;return {data:page()};}});for(const filters of [{q:''},{q:'x'.repeat(257)},{status:'favorite'},{favorite:'true'},{page:0},{page_size:51}])await assert.rejects(api.list(filters));assert.equal(calls,0);});
