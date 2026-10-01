import {ApiError} from './client.js';
const invalid=()=>{throw new ApiError(200,'invalid_response','Retained result comparison is unavailable');};
const freeze=value=>{if(value&&typeof value==='object'){Object.values(value).forEach(freeze);Object.freeze(value);}return value;};
function text(value,max,min=1){if(typeof value!=='string'||[...value].length<min||[...value].length>max||!value.isWellFormed())invalid();return value;}
function id(value){text(value,64);if(/[\u0000-\u001f\u007f]/.test(value))invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function one(value,choices){if(!choices.includes(value))invalid();return value;}
function list(value,max){if(!Array.isArray(value)||value.length>max)invalid();return value;}
function nullable(value,parse){return value===null?null:parse(value);}
function instant(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,23)!==value.slice(0,23))invalid();return value;}
function date(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value)invalid();return value;}
function body(value,kind){
 if(!value)invalid();const citationIds=list(value.citation_ids,1000).map(id),sections=list(value.sections,100).map(v=>{if(!v)invalid();return {heading:text(v.heading,512),text:text(v.text,200000,0),citationIds:list(v.citation_ids,1000).map(id)};});
 const result={title:text(value.title,512),sections,citationIds};
 if(kind==='report'){
  result.type=one(value.type,['daily','weekly','monthly']);result.periodStart=instant(value.period_start);result.periodEnd=instant(value.period_end);if(result.periodStart>=result.periodEnd)invalid();result.timezone=one(value.timezone,['Asia/Shanghai']);
  if(value.learning_task===null)result.learningTask=null;else{const v=value.learning_task;if(!v||result.type!=='daily')invalid();result.learningTask={packageId:id(v.package_id),kind:one(v.object_kind,['node','question','project']),id:id(v.object_id),title:text(v.title,512),reason:text(v.reason,2048)};}
 }else{result.category=one(value.category,[kind]);result.entityId=id(value.entity_id);}return result;
}
function coverage(value){if(!value)invalid();return {state:one(value.state,['complete','partial','no_input']),missingInputs:list(value.missing_inputs,1000).map(v=>{if(!v)invalid();return {kind:text(v.kind,32),id:nullable(v.id,id),businessDate:nullable(v.business_date,date),reason:text(v.reason,256)};}),missingDates:list(value.missing_dates,366).map(date),noUpdateDates:list(value.no_update_dates,366).map(date)};}
export function createJobResultComparisonApi(client){return Object.freeze({
 async read(identifier,options={}){
  const target=id(identifier);if(!options||typeof options!=='object'||Array.isArray(options)||Object.keys(options).some(k=>k!=='signal'))throw new TypeError('Invalid comparison options');
  const {signal}=options,{data}=await client.request('GET','/admin/jobs/'+encodeURIComponent(target)+'/result-comparison',{signal});
  if(!data||data.job_id!==target||new TextEncoder().encode(JSON.stringify(data)).length>1048576)invalid();const common={jobId:target,jobRevision:number(data.job_revision),state:one(data.state,['unavailable','comparison'])};
  if(data.state==='unavailable'){if(['current_body','candidate_body','result_hash','can_apply'].some(k=>Object.hasOwn(data,k)))invalid();return freeze({...common,reason:one(data.reason,['not_ready','no_retained_result','not_content_result','no_current_content','unproven_input','unproven_current_version'])});}
  const contentKind=one(data.content_kind,['report','agent','skill','workflow','model']),currentBody=body(data.current_body,contentKind),candidateBody=body(data.candidate_body,contentKind);
  if(contentKind==='report'){for(const key of ['type','periodStart','periodEnd','timezone'])if(currentBody[key]!==candidateBody[key])invalid();}else if(currentBody.entityId!==candidateBody.entityId)invalid();
  if(typeof data.can_apply!=='boolean'||typeof data.result_hash!=='string'||!/^[0-9a-f]{64}$/.test(data.result_hash))invalid();const generation=number(data.generation);if(generation<1)invalid();
  const blockingReasons=list(data.blocking_reasons,5).map(v=>one(v,['already_applied','discarded','superseded','invalid_evidence','application_state_not_conflict']));if(new Set(blockingReasons).size!==blockingReasons.length||data.can_apply!==(blockingReasons.length===0))invalid();
  return freeze({...common,contentKind,contentId:id(data.content_id),currentContentRevision:number(data.current_content_revision),currentBodyRevision:number(data.current_body_revision),currentBody,candidateBody,resultHash:data.result_hash,inputSnapshotId:id(data.input_snapshot_id),generation,inputCutoffAt:instant(data.input_cutoff_at),coverage:coverage(data.coverage),canApply:data.can_apply,blockingReasons});
 }
});}
