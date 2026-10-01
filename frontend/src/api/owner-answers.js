import {ApiError} from './client.js';
import {actionResult} from './action-result.js';
const states=['draft','approved','published','withdrawn'],generation=['not_generated','generated','reviewed','needs_revision'];
const detailFields=['package_id','question_id','question_title','revision','answer_revision','answer','answer_state','generation_state','review_state','publicly_readable'];
const receiptFields=['operation_ref','result_resource_ref','result_revision','generation','cutoff','coverage','affected','next_cursor'];
const invalid=()=>{throw new ApiError(200,'invalid_response','Current answer is unavailable');};
const freeze=value=>{if(value&&typeof value==='object'){for(const child of Object.values(value))freeze(child);Object.freeze(value);}return value;};
function closed(value,fields){if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==fields.length||fields.some(k=>!Object.hasOwn(value,k)))throw new TypeError('Invalid answer fields');}
function options(value,fields){if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).some(k=>!fields.includes(k)))throw new TypeError('Invalid answer options');}
function text(value,min,max){if(typeof value!=='string'||[...value].length<min||[...value].length>max||[...value].some(unit=>unit.length===1&&/[\ud800-\udfff]/.test(unit)))throw new TypeError('Invalid answer text');return value;}
function id(value){text(value,1,64);if(/[\u0000-\u001f\u007f]/.test(value))throw new TypeError('Invalid answer identity');return value;}
function number(value,min=0){if(!Number.isSafeInteger(value)||value<min)throw new TypeError('Invalid answer revision');return value;}
const nullable=(value,parse)=>value===null?null:parse(value);
function one(value,allowed){if(!allowed.includes(value))throw new TypeError('Invalid answer state');return value;}
function byteLimit(value,max){if(new TextEncoder().encode(JSON.stringify(value)).length>max)throw new TypeError('Answer exceeds transport limit');}
function base(packageId,questionId){return '/admin/learning/packages/'+encodeURIComponent(id(packageId))+'/questions/'+encodeURIComponent(id(questionId))+'/answer';}
export function ownerAnswerDetail(value,packageId,questionId){try{
 closed(value,detailFields);byteLimit(value,1048576);if(id(value.package_id)!==packageId||id(value.question_id)!==questionId)invalid();
 const result={packageId,questionId,questionTitle:nullable(value.question_title,v=>text(v,1,512)),revision:nullable(value.revision,v=>number(v,1)),answerRevision:nullable(value.answer_revision,number),answer:nullable(value.answer,v=>text(v,0,50000)),answerState:nullable(value.answer_state,v=>one(v,states)),generationState:nullable(value.generation_state,v=>one(v,generation)),reviewState:one(value.review_state,['unknown','unreviewed','current','stale']),publiclyReadable:value.publicly_readable};
 if(result.publiclyReadable!==null&&typeof result.publiclyReadable!=='boolean'||(result.answerState==='published'||result.reviewState==='current')&&(result.answer===null||result.answer==='')||result.publiclyReadable===true&&(result.answerState!=='published'||result.reviewState!=='current'))invalid();return freeze(result);
}catch{invalid();}}
function headers(value){options(value,['fields','signal','idempotencyKey']);if(typeof value.idempotencyKey!=='string'||!/^[!-~]{1,128}$/.test(value.idempotencyKey))throw new TypeError('Stable answer command key required');}
function revision(value){number(value,1);if(value===Number.MAX_SAFE_INTEGER)throw new TypeError('Answer revision exhausted');return value;}
function receipt(value,packageId,questionId,expected){try{
 closed(value,receiptFields);byteLimit(value,1048576);closed(value.result_resource_ref,['kind','id','package_id']);if(!Array.isArray(value.affected))invalid();for(const ref of value.affected){closed(ref,['kind','id','package_id']);text(ref.kind,1,32);id(ref.id);nullable(ref.package_id,id);}id(value.operation_ref);nullable(value.next_cursor,v=>text(v,1,512));
 if(value.generation!==null||value.cutoff!==null||value.coverage!==null||value.result_resource_ref.kind!=='question'||id(value.result_resource_ref.id)!==questionId||id(value.result_resource_ref.package_id)!==packageId||number(value.result_revision)!==expected+1)invalid();return actionResult(value);
}catch{invalid();}}
export function createOwnerAnswersApi(client){return Object.freeze({
 async read(packageId,questionId,opts={}){options(opts,['signal']);const path=base(packageId,questionId),{data}=await client.request('GET',path,{signal:opts.signal});return ownerAnswerDetail(data,packageId,questionId);},
 async write(packageId,questionId,opts={}){headers(opts);const path=base(packageId,questionId);closed(opts.fields,['expected_revision','answer']);const body=freeze({expected_revision:revision(opts.fields.expected_revision),answer:text(opts.fields.answer,0,50000)});byteLimit(body,262144);const {data}=await client.request('PUT',path,{body,signal:opts.signal,idempotencyKey:opts.idempotencyKey});return receipt(data,packageId,questionId,body.expected_revision);},
 async action(packageId,questionId,opts={}){headers(opts);const path=base(packageId,questionId);closed(opts.fields,['expected_revision','action','reason']);const reason=text(opts.fields.reason,1,2048);if(!reason.trim())throw new TypeError('Answer action reason required');const body=freeze({expected_revision:revision(opts.fields.expected_revision),action:one(opts.fields.action,['approve','reject','publish','withdraw']),reason});byteLimit(body,262144);const {data}=await client.request('POST',path+'/actions',{body,signal:opts.signal,idempotencyKey:opts.idempotencyKey});return receipt(data,packageId,questionId,body.expected_revision);}
});}
