import test from 'node:test';
import assert from 'node:assert/strict';
import {createInterpretationsApi} from '../src/api/interpretations.js';
const value=()=>({id:'fictional-detail',kind:'model',title:'Fictional interpretation',revision:2,body_revision:1,publication_state:'published',published_at:null,input_snapshot_id:null,generation:null,input_cutoff_at:null,coverage:{state:'complete',missing_inputs:[],missing_dates:[],no_update_dates:[]},review_required:false,blocking_reasons:[],affected_by_correction:false,supersedes_job_id:null,name:'Fictional tool',provider:null,tags:[],description:null,evidence_time:null,metrics:[],stars:null,stars_growth:null,body:{title:'Fictional current body',category:'model',entity_id:'fictional-entity',sections:[{heading:'Fictional section',text:'Fictional body',citation_ids:['fictional-cite']}],citation_ids:['fictional-cite']},citations:[{id:'fictional-cite',kind:'catalog_evidence',title:'Fictional source',source_url:'https://example.invalid/doc',revision:null,body_hash:null}],internal_payload:'Fictional secret-free extra'});
test('current interpretation detail binds path and category, validates sources and drops internal extras',async()=>{
  let call;const api=createInterpretationsApi({request:async(...args)=>{call=args;return {data:value()};}});
  const signal=new AbortController().signal,result=await api.detail('fictional-detail',{kind:'model',signal});
  assert.equal(call[1],'/interpretations/fictional-detail');assert.equal(call[2].signal,signal);assert.equal(result.entityId,'fictional-entity');assert.equal(result.sections[0].text,'Fictional body');assert.equal(result.citations[0].sourceUrl,'https://example.invalid/doc');assert.equal(result.internal_payload,undefined);assert.ok(Object.isFrozen(result.sections[0]));
});
test('detail refuses identity confusion, unknown citations and unsafe links without historical fallback',async()=>{
  let data;const api=createInterpretationsApi({request:async()=>({data})});
  for(const mutate of [v=>v.id='fictional-other',v=>v.kind='agent',v=>v.body.category='agent',v=>v.citations[0].source_url='javascript:alert(1)',v=>v.citations[0].source_url='https://fictional-user:fictional-pass@example.invalid',v=>v.body.sections[0].citation_ids=['fictional-unknown'],v=>v.body.citation_ids=[],v=>v.citations.push(v.citations[0]),v=>v.citations[0].body_hash='bad',v=>v.publication_state='draft']){data=value();mutate(data);await assert.rejects(api.detail('fictional-detail',{kind:'model'}));}
  let calls=0;const invalid=createInterpretationsApi({request:async()=>calls++});await assert.rejects(invalid.detail('fictional-detail',{kind:'report'}));assert.equal(calls,0);
});
