import test from 'node:test';
import assert from 'node:assert/strict';
import {createGenerationIntentsApi} from '../src/api/generation-intents.js';
const command=()=>({kind:'daily',period_start:'2026-01-01T16:00:00.000000Z',period_end:'2026-01-02T16:00:00.000000Z',timezone:'Asia/Shanghai',mode:'initial',reason:'Fictional intent'});
const receipt=()=>({id:'fictional-intent',record_kind:'generation_intent',revision:4,state:'waiting_input',intent_id:'fictional-intent',intent_revision:2,content_id:null,report_mode:null,input_state:'waiting_input',input_snapshot_id:null,input_cutoff_at:null,coverage:{state:'partial',missing_inputs:[],missing_dates:['2026-01-02'],no_update_dates:[]},next_eligible_at:null,waiting_reasons:['Fictional missing input'],raw_payload:'Fictional hidden payload'});
test('initial and supplement commands preserve exact fixed period CAS/key and immutable safe intent projection',async()=>{
 let calls=[];const signal=new AbortController().signal,api=createGenerationIntentsApi({request:async(...args)=>{calls.push(args);return {data:receipt()};}});
 const body=command(),result=await api.submit(body,{signal,idempotencyKey:'fictional-key'});
 assert.deepEqual(calls[0],['POST','/admin/reports/generation-intents',{signal,idempotencyKey:'fictional-key',body}]);
 assert.equal(result.recordKind,'generation_intent');assert.equal(result.intentRevision,2);assert.equal(result.contentId,null);assert.equal(result.inputState,'waiting_input');assert.equal(result.raw_payload,undefined);assert.ok(Object.isFrozen(result.coverage.missingDates));
 const supplement={...body,mode:'supplement',intent_revision:2};await api.submit(supplement,{idempotencyKey:'fictional-second'});assert.deepEqual(calls[1][2].body,supplement);
});
test('safe read binds exact intent ID and preserves explicit unknown fields',async()=>{
 let call;const data=receipt();data.intent_revision=null;data.input_state=null;const api=createGenerationIntentsApi({request:async(...args)=>{call=args;return {data};}});const value=await api.read('fictional-intent');assert.deepEqual(call,['GET','/admin/jobs/fictional-intent',{signal:undefined}]);assert.equal(value.intentRevision,null);assert.equal(value.inputState,null);
 data.id='fictional-other';await assert.rejects(api.read('fictional-intent'),{code:'invalid_response'});
});
test('wrong record kind malformed coverage or missing required summary never become accepted intent',async()=>{
 for(const change of [v=>v.record_kind='logical_task',v=>delete v.intent_revision,v=>v.intent_revision=9007199254740992,v=>v.content_id='fictional\nunsafe',v=>v.input_state='completed',v=>v.coverage.missing_dates=['2026-02-30'],v=>v.input_cutoff_at='2026-02-30T16:00:00.000000Z',v=>v.waiting_reasons=[null],v=>v.id='\ud800']){const data=receipt();change(data);await assert.rejects(createGenerationIntentsApi({request:async()=>({data})}).submit(command(),{idempotencyKey:'fictional-key'}),{code:'invalid_response'});}
});
test('invalid closed command fails before network; unknown and expired errors retain identity',async()=>{
 let calls=0;const failure={status:409,code:'result_expired'},api=createGenerationIntentsApi({request:async()=>{calls++;throw failure;}});
 for(const edit of [v=>v.mode='recover',v=>v.intent_revision=0,v=>v.mode='supplement',v=>v.reason='  ',v=>v.timezone='UTC',v=>v.period_end=v.period_start,v=>v.period_start='2026-02-30T16:00:00.000000Z',v=>v.extra='Fictional']){let body=command();edit(body);await assert.rejects(api.submit(body,{idempotencyKey:'fictional-key'}));}assert.equal(calls,0);
 await assert.rejects(api.submit(command(),{idempotencyKey:'fictional key'}));assert.equal(calls,0);
 await assert.rejects(api.submit(command(),{idempotencyKey:'fictional-key'}),e=>e===failure);assert.equal(calls,1);
});
