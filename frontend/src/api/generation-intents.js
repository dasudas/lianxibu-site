import {ApiError} from './client.js';
const invalid=()=>{throw new ApiError(200,'invalid_response','Generation intent is unavailable');};
const freeze=value=>{if(value&&typeof value==='object'){for(const child of Object.values(value))freeze(child);Object.freeze(value);}return value;};
function text(value,max){if(typeof value!=='string'||!value||[...value].length>max||!value.isWellFormed())invalid();return value;}
function id(value){text(value,64);if(/[\u0000-\u001f\u007f]/.test(value))invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function one(value,choices){if(!choices.includes(value))invalid();return value;}
function nullable(value,parse){return value===null?null:parse(value);}
function list(value,max){if(!Array.isArray(value)||value.length>max)invalid();return value;}
function instant(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,23)!==value.slice(0,23))invalid();return value;}
function date(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value)invalid();return value;}
function coverage(value){if(!value)invalid();return {state:one(value.state,['complete','partial','no_input']),missingInputs:list(value.missing_inputs,1000).map(v=>{if(!v)invalid();return {kind:text(v.kind,32),id:nullable(v.id,id),businessDate:nullable(v.business_date,date),reason:text(v.reason,256)};}),missingDates:list(value.missing_dates,366).map(date),noUpdateDates:list(value.no_update_dates,366).map(date)};}
function intent(value,expectedId){
 if(!value||value.record_kind!=='generation_intent'||expectedId!==undefined&&value.id!==expectedId)invalid();
 return freeze({id:id(value.id),recordKind:'generation_intent',revision:number(value.revision),state:text(value.state,32),intentId:nullable(value.intent_id,id),intentRevision:nullable(value.intent_revision,number),contentId:nullable(value.content_id,id),reportMode:nullable(value.report_mode,v=>one(v,['normal','no_updates','coverage_only'])),inputState:nullable(value.input_state,v=>one(v,['ready','no_input','waiting_input'])),inputSnapshotId:nullable(value.input_snapshot_id,id),inputCutoffAt:nullable(value.input_cutoff_at,instant),coverage:coverage(value.coverage),nextEligibleAt:nullable(value.next_eligible_at,instant),waitingReasons:list(value.waiting_reasons,100).map(v=>text(v,256))});
}
function options(value,allowed){if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).some(k=>!allowed.includes(k)))throw new TypeError('Invalid intent options');return value;}
function command(value){
 if(!value||typeof value!=='object'||Array.isArray(value)||!['initial','supplement'].includes(value.mode))throw new TypeError('Invalid generation intent mode');
 const keys=['kind','period_start','period_end','timezone','mode','reason',...(value.mode==='supplement'?['intent_revision']:[])];
 if(Object.keys(value).length!==keys.length||keys.some(k=>!Object.hasOwn(value,k)))throw new TypeError('Invalid generation intent fields');
 const result={kind:one(value.kind,['daily','weekly','monthly']),period_start:instant(value.period_start),period_end:instant(value.period_end),timezone:one(value.timezone,['Asia/Shanghai']),mode:value.mode,reason:text(value.reason,2048)};
 if(result.period_start>=result.period_end||!result.reason.trim())throw new TypeError('Invalid generation intent period/reason');
 if(value.mode==='supplement')result.intent_revision=number(value.intent_revision);return Object.freeze(result);
}
export function createGenerationIntentsApi(client){return Object.freeze({
 async submit(fields,query={}){
  const {signal,idempotencyKey}=options(query,['signal','idempotencyKey']);if(typeof idempotencyKey!=='string'||!/^[!-~]{1,128}$/.test(idempotencyKey))throw new TypeError('Stable intent key required');
  const body=command(fields),{data}=await client.request('POST','/admin/reports/generation-intents',{signal,idempotencyKey,body});return intent(data);
 },
 async read(identifier,query={}){const target=id(identifier),{signal}=options(query,['signal']);const {data}=await client.request('GET','/admin/jobs/'+encodeURIComponent(target),{signal});return intent(data,target);}
});}
