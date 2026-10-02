import {ApiError} from './client.js';
const fail=()=>{throw new TypeError('Invalid source member action');};
function closed(v,keys){if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))fail();}
function text(v,max){if(typeof v!=='string'||!v.length||!v.isWellFormed()||[...v].length>max)fail();return v;}
function id(v,max=64){text(v,max);if(/[\u0000-\u001f\u007f-\u009f]/.test(v))fail();return v;}
function num(v,min=0){if(!Number.isSafeInteger(v)||v<min)fail();return v;}
function date(v){text(v,10);if(v.length!==10||!/^\d{4}-\d{2}-\d{2}$/.test(v)||new Date(v).toISOString().slice(0,10)!==v)fail();return v;}
function time(v){text(v,27);if(v.length!==27||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(v)||new Date(v).toISOString().slice(0,23)!==v.slice(0,23))fail();return v;}
const nullable=(v,parse)=>v===null?null:parse(v);
function refs(v,max,parse){if(!Array.isArray(v)||v.length>max)fail();return Object.freeze(v.map(x=>parse(x)));}
function ref(v){closed(v,['kind','id','package_id']);return Object.freeze({kind:id(v.kind,32),id:id(v.id),packageId:nullable(v.package_id,id)});}
function coverage(v){closed(v,['state','missing_inputs','missing_dates','no_update_dates']);if(!['complete','partial','no_input'].includes(v.state))fail();const missingInputs=refs(v.missing_inputs,1000,x=>{closed(x,['kind','id','business_date','reason']);return Object.freeze({kind:text(x.kind,32),id:nullable(x.id,id),businessDate:nullable(x.business_date,date),reason:text(x.reason,256)});}),missingDates=refs(v.missing_dates,366,date),noUpdateDates=refs(v.no_update_dates,366,date);if(v.state==='complete'&&(missingInputs.length||missingDates.length)||new Set(missingDates).size!==missingDates.length||new Set(noUpdateDates).size!==noUpdateDates.length||missingDates.some(d=>noUpdateDates.includes(d)))fail();return Object.freeze({state:v.state,missingInputs,missingDates,noUpdateDates});}
function command(t,o){
 if(!o||typeof o!=='object'||Array.isArray(o)||Object.keys(o).some(k=>!['action','reason','entityId','decision','expectedArticleRevision','evidenceRefs','idempotencyKey','signal'].includes(k)))fail();
 if(!['confirm_new','link_existing','exclude','resolve_projection','revalidate','seal'].includes(o.action))fail();text(o.reason,2048);if(!o.reason.trim())fail();
 if(typeof o.idempotencyKey!=='string'||o.idempotencyKey.length>128||!/^[!-~]+$/.test(o.idempotencyKey)||o.idempotencyKey.endsWith('\n'))fail();
 const link=o.action==='link_existing',projection=o.action==='resolve_projection';
 if(Object.hasOwn(o,'entityId')!==link||['decision','expectedArticleRevision','evidenceRefs'].some(k=>Object.hasOwn(o,k)!==projection))fail();
 const body={expected_revision:t.revision,action:o.action,reason:o.reason};
 if(link)body.entity_id=id(o.entityId);
 if(projection){if(!['keep_historical','promote'].includes(o.decision))fail();body.decision=o.decision;body.expected_article_revision=num(o.expectedArticleRevision);body.evidence_refs=refs(o.evidenceRefs,100,id);if(!body.evidence_refs.length||new Set(body.evidence_refs).size!==body.evidence_refs.length)fail();}
 return Object.freeze(body);
}
function canonical(v){if(Array.isArray(v))return '['+v.map(canonical).join(',')+']';if(v&&typeof v==='object')return '{'+Object.keys(v).sort().map(k=>JSON.stringify(k)+':'+canonical(v[k])).join(',')+'}';return JSON.stringify(v);}
async function fingerprint(body){const bytes=new TextEncoder().encode('action-v1\n'+canonical(body));return [...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(n=>n.toString(16).padStart(2,'0')).join('');}
function receipt(v,t,body,hash){try{
 if(new TextEncoder().encode(JSON.stringify(v)).length>1048576)fail();closed(v,['operation_ref','result_resource_ref','result_revision','generation','cutoff','coverage','affected','next_cursor','command_ref']);const c=v.command_ref;closed(c,['source_id','run_id','item_id','expected_revision','action','command_hash']);
 if(c.source_id!==t.sourceId||c.run_id!==t.runId||c.item_id!==t.itemId||c.expected_revision!==t.revision||c.action!==body.action||c.command_hash!==hash)fail();
 const resource=ref(v.result_resource_ref),resultRevision=num(v.result_revision),generation=nullable(v.generation,x=>num(x,1));if(resource.packageId!==null)fail();
 if(resource.kind==='source_run_item'){if(resource.id!==t.itemId||t.revision===Number.MAX_SAFE_INTEGER||resultRevision!==t.revision+1||generation!==null)fail();}
 else if(resource.kind==='source_run'){if(resource.id===t.runId||generation===null)fail();}else fail();
 return Object.freeze({operationRef:id(v.operation_ref),resource,resultRevision,generation,cutoff:nullable(v.cutoff,time),coverage:nullable(v.coverage,coverage),affected:refs(v.affected,1000,ref),nextCursor:nullable(v.next_cursor,x=>text(x,4096)),commandRef:Object.freeze({sourceId:c.source_id,runId:c.run_id,itemId:c.item_id,expectedRevision:c.expected_revision,action:c.action,commandHash:c.command_hash})});
 }catch{throw new ApiError(200,'invalid_response','Member receipt cannot be verified');}}
export function createSourceItemActionsApi(client){return Object.freeze({async execute(target,options={}){
 closed(target,['sourceId','runId','itemId','revision']);const t=Object.freeze({sourceId:id(target.sourceId,80),runId:id(target.runId),itemId:id(target.itemId),revision:num(target.revision)}),body=command(t,options),hash=await fingerprint(body);
 const {data}=await client.request('POST','/admin/source-runs/'+encodeURIComponent(t.runId)+'/items/'+encodeURIComponent(t.itemId)+'/actions',{body,idempotencyKey:options.idempotencyKey,signal:options.signal});return receipt(data,t,body,hash);
 }});}
