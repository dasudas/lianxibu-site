import {ApiError} from './client.js';
import {jobProjection} from './jobs.js';
import {adHocPrompt} from './job-actions.js';
import {canonicalImportJson} from '../core/learning-import-plan.js';
const bad=()=>{throw new TypeError('Invalid temporary question view');};
function closed(v,fields){if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==fields.length||fields.some(f=>!Object.hasOwn(v,f)))bad();}
function id(v){if(typeof v!=='string'||!v||!v.isWellFormed()||[...v].length>64||/[\u0000-\u001f\u007f]/.test(v))bad();return v;}
function text(v,min,max){if(typeof v!=='string'||!v.isWellFormed()||[...v].length<min||[...v].length>max)bad();return v;}
function hash(v){if(typeof v!=='string'||!/^[0-9a-f]{64}$(?![\s\S])/.test(v))bad();return v;}
function emptyCitations(v){if(!Array.isArray(v)||v.length!==0)bad();return Object.freeze([]);}
function freeze(v){if(v&&typeof v==='object'){Object.values(v).forEach(freeze);Object.freeze(v);}return v;}
async function result(v){
 closed(v,['schema_version','input_hash','payload','result_hash']);if(v.schema_version!=='1')bad();hash(v.input_hash);hash(v.result_hash);closed(v.payload,['kind','title','sections','citation_ids']);if(v.payload.kind!=='ad_hoc')bad();text(v.payload.title,1,512);emptyCitations(v.payload.citation_ids);if(!Array.isArray(v.payload.sections)||v.payload.sections.length>100)bad();
 for(const section of v.payload.sections){closed(section,['heading','text','citation_ids']);text(section.heading,1,512);text(section.text,0,200000);emptyCitations(section.citation_ids);}
 if(new TextEncoder().encode(JSON.stringify(v)).length>262144)bad();
 const bytes=new TextEncoder().encode('ai-result-v1\n'+canonicalImportJson({schema_version:'1',input_hash:v.input_hash,payload:v.payload}));const digest=await globalThis.crypto.subtle.digest('SHA-256',bytes);const actual=[...new Uint8Array(digest)].map(x=>x.toString(16).padStart(2,'0')).join('');if(actual!==v.result_hash)bad();
 return freeze(structuredClone(v));
}
/** The server owns native authority. This consumer checks the closed wire and complete result hash. */
export function createAdHocQuestionApi(client){return Object.freeze({async read(identifier,options={}){
 id(identifier);if(!options||typeof options!=='object'||Array.isArray(options)||Object.keys(options).some(k=>k!=='signal'))bad();
 const {data}=await client.request('GET','/admin/jobs/'+encodeURIComponent(identifier)+'/ad-hoc',{signal:options.signal});
 try{
  // Capture once before async digest; caller-owned transport objects cannot change identity mid-validation.
  const v=structuredClone(data);if(new TextEncoder().encode(JSON.stringify(v)).length>1048576)bad();closed(v,['intent','original_request','prepared_job','retained_result']);closed(v.original_request,['prompt']);const prompt=adHocPrompt(v.original_request.prompt),intent=jobProjection(v.intent);
  if(intent.id!==identifier||intent.intentId!==identifier||intent.recordKind!=='generation_intent'||intent.taskType!=='ad_hoc_ai'||intent.priority!==0||intent.callCount!==0||intent.maxAttempts!==null||intent.remainingAttempts!==0||intent.retryExhausted||intent.contentId!==null||intent.reportMode!==null||intent.attempts.length!==0)bad();
  const prepared=v.prepared_job===null?null:jobProjection(v.prepared_job);
  if(prepared&&(prepared.recordKind!=='logical_task'||prepared.taskType!=='ad_hoc_ai'||prepared.id===identifier||prepared.intentId!==identifier||prepared.intentRevision!==intent.revision||prepared.priority!==14||prepared.maxAttempts!==3||prepared.contentId!==null||prepared.reportMode!==null||prepared.inputState!=='ready'||prepared.inputSnapshotId!==prepared.id||prepared.inputCutoffAt===null||prepared.slotState!==intent.slotState||prepared.slotRevision!==intent.slotRevision||prepared.occupiedAttemptId!==intent.occupiedAttemptId))bad();
  if(prepared===null&&v.retained_result!==null||v.retained_result!==null&&prepared.callCount<1)bad();
  const retained=v.retained_result===null?null:await result(v.retained_result);
  return Object.freeze({intent,originalRequest:Object.freeze({prompt}),preparedJob:prepared,retainedResult:retained});
 }catch{throw new ApiError(200,'invalid_response','Original temporary question and answer cannot be verified');}
}});}
