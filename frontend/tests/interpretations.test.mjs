import test from 'node:test';
import assert from 'node:assert/strict';
import {createInterpretationsApi} from '../src/api/interpretations.js';
const item=kind=>({id:'fictional-interpretation',kind,title:'Fictional interpretation',revision:2,body_revision:1,publication_state:'published',published_at:null,input_snapshot_id:null,generation:null,input_cutoff_at:null,coverage:{state:'partial',missing_inputs:[],missing_dates:[],no_update_dates:[]},review_required:false,blocking_reasons:[],affected_by_correction:true,supersedes_job_id:null,name:'Fictional tool',provider:null,tags:['Fictional tag'],description:null,evidence_time:null,metrics:[{benchmark:'Fictional benchmark',benchmark_version:'Fictional version',task:'Fictional task',score:null,unit:'points',evaluated_at:null,higher_is_better:true,evaluation_scope:'configuration',provenance:'author_self_report'}],stars:null,stars_growth:null,body_text:'Fictional internal extra'});
test('public interpretation list validates exact kind, publication and pagination, closed profile and unknown metrics',async()=>{
    let request;for(const [kind,route]of Object.entries({model:'models',agent:'agents',skill:'skills',workflow:'workflows'})){
        const api=createInterpretationsApi({request:async(...args)=>{request=args;return {data:{items:[item(kind)],page:1,page_size:20,total:1}};}});const result=await api.list({kind});
        assert.equal(request[1],'/interpretations');assert.deepEqual(request[2].query,{kind,page:1,page_size:20});assert.equal(result.items[0].href,`/${route}/fictional-interpretation`);assert.equal(result.items[0].bodyRevision,1);assert.equal(result.items[0].provider,null);assert.equal(result.items[0].stars,null);assert.equal(result.items[0].metrics[0].score,null);assert.equal(result.items[0].body_text,undefined);assert.ok(Object.isFrozen(result.items[0].metrics[0]));
    }
});
test('hidden content, kind confusion, invalid profile metrics and misleading service pages fail closed',async()=>{
    let value=item('model'),total=1;const api=createInterpretationsApi({request:async()=>({data:{items:[value],page:1,page_size:20,total}})});
    for(const mutate of [v=>v.kind='agent',v=>v.publication_state='draft',v=>v.review_required=true,v=>v.blocking_reasons=['Fictional block'],v=>v.stars=9007199254740992,v=>v.metrics[0].score='1e8',v=>v.metrics[0].score=0,v=>v.metrics[0].unit='arbitrary',v=>v.evidence_time='2026-02-30T00:00:00.000000Z',v=>v.tags=null]){value=item('model');mutate(value);await assert.rejects(api.list({kind:'model'}),e=>e.code==='invalid_response');}
    value=item('model');total=0;await assert.rejects(api.list({kind:'model'}));
    let calls=0;const invalid=createInterpretationsApi({request:async()=>{calls++;}});for(const filters of [{kind:'report'},{kind:'model',page:0},{kind:'model',page_size:51}])await assert.rejects(invalid.list(filters));assert.equal(calls,0);
});
