import { ApiError } from './client.js';
import { recordWriteRequest } from './paths.js';
const kinds = ['article','node','question','project'], statuses = ['not_started','planned','learning','completed'], assessments = ['unassessed','not_mastered','partial','mastered'];
const invalid = () => { throw new ApiError(200,'invalid_response','Private record is unavailable'); };
function text(value,max,empty=false){if(typeof value!=='string'||(!empty&&!value)||[...value].length>max)invalid();return value;}
function id(value){text(value,64);if(/[\u0000-\u001f\u007f]/.test(value))invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function flag(value){if(typeof value!=='boolean')invalid();return value;}
function optional(value,parse){return value===null?null:parse(value);}
function one(value,values){if(!values.includes(value))invalid();return value;}
function instant(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,23)!==value.slice(0,23))invalid();return value;}
function url(value){text(value,2000);if(!value.startsWith('https://')||/[\u0000-\u0020\u007f\\]/.test(value))invalid();let result;try{result=new URL(value);}catch{invalid();}if(!result.hostname||result.username||result.password)invalid();return value;}
function key(value){if(!value)invalid();const kind=one(value.kind,kinds),packageId=kind==='article'?(value.packageId===null?null:invalid()):id(value.packageId);return {kind,id:id(value.id),packageId};}
function same(record,identity){if(record.kind!==identity.kind||record.id!==identity.id||record.packageId!==identity.packageId)invalid();return record;}
export function recordProjection(value){
  if(!value)invalid();const identity=key({kind:value.object_kind,id:value.object_id,packageId:value.package_id});
  return Object.freeze({...identity,revision:number(value.revision),status:one(value.status,statuses),selfAssessment:one(value.self_assessment,assessments),note:text(value.note,10000,true),artifactUrl:optional(value.artifact_url,url),latestAnswerText:optional(value.latest_answer_text,v=>text(v,50000,true)),latestSubmissionUrl:optional(value.latest_submission_url,url),confirmedBodyRevision:optional(value.confirmed_body_revision,number),updatedAt:instant(value.updated_at),isRead:flag(value.is_read),isFavorite:flag(value.is_favorite),isIgnored:flag(value.is_ignored)});
}
function command(identity,fields){
  if(!fields||typeof fields!=='object'||Array.isArray(fields))invalid();
  const common=['expected_revision','status','self_assessment','note','artifact_url'];const extra=identity.kind==='article'?['is_read','is_favorite','is_ignored']:identity.kind==='question'?['latest_answer_text']:identity.kind==='project'?['latest_submission_url']:[];
  const keys=[...common,...extra];if(Object.keys(fields).length!==keys.length||keys.some(k=>!Object.hasOwn(fields,k)))invalid();
  const result={expected_revision:number(fields.expected_revision),status:one(fields.status,statuses),self_assessment:one(fields.self_assessment,assessments),note:text(fields.note,10000,true),artifact_url:optional(fields.artifact_url,url)};
  if(identity.kind==='article')for(const k of extra)result[k]=flag(fields[k]);
  if(identity.kind==='question')result.latest_answer_text=text(fields.latest_answer_text,50000,true);
  if(identity.kind==='project')result.latest_submission_url=optional(fields.latest_submission_url,url);
  return result;
}
export function createRecordsApi(client){return Object.freeze({
  async list({kind='article',packageId=null,page=1,page_size=20,signal}={}){
    one(kind,kinds);if(kind==='article'?packageId!==null:!packageId)invalid();if(packageId!==null)id(packageId);
    if(!Number.isSafeInteger(page)||page<1||!Number.isSafeInteger(page_size)||page_size<1||page_size>50)invalid();
    const {data}=await client.request('GET','/private/records',{signal,query:{kind,package_id:packageId,page,page_size}});
    if(!data||data.page!==page||data.page_size!==page_size||!Array.isArray(data.items)||data.items.length>page_size)invalid();const total=number(data.total),seen=new Set();
    const items=data.items.map(value=>{const record=recordProjection(value);if(record.kind!==kind||record.packageId!==packageId||seen.has(record.id))invalid();seen.add(record.id);return record;});
    if(items.length>Math.max(0,total-(page-1)*page_size))invalid();return Object.freeze({items:Object.freeze(items),page,pageSize:page_size,total});
  },
  async save(identity,fields,{signal,idempotencyKey}={}){
    const target=key(identity),request=recordWriteRequest(target.kind,target.id,target.packageId,command(target,fields));
    const {data}=await client.request('PUT',request.path,{signal,idempotencyKey,body:request.body});return same(recordProjection(data),target);
  }
});}
