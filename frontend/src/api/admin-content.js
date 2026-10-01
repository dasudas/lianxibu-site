import {actionResult} from './action-result.js';
import {ApiError} from './client.js';
import {createReportsApi,reportDetail} from './reports.js';
import {createInterpretationsApi,interpretationDetail} from './interpretations.js';
const kinds=['report','model','agent','skill','workflow'],states=['draft','approved','published','withdrawn'],types=['daily','weekly','monthly'];
const invalid=()=>{throw new ApiError(200,'invalid_response','Content catalog is unavailable');};
const freeze=value=>{if(value&&typeof value==='object'){for(const child of Object.values(value))freeze(child);Object.freeze(value);}return value;};
function text(value,max){if(typeof value!=='string'||!value||[...value].length>max)invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function one(value,choices){if(!choices.includes(value))invalid();return value;}

function closed(value,keys){if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==keys.length||keys.some(k=>!Object.hasOwn(value,k)))throw new TypeError('Invalid content command fields');}
function identifier(value){text(value,64);if(/[\u0000-\u001f\u007f]/.test(value))throw new TypeError('Invalid content identity');return value;}
function references(value){if(!Array.isArray(value)||value.length>1000)throw new TypeError('Invalid content references');const result=value.map(identifier);if(new Set(result).size!==result.length)throw new TypeError('Duplicate content reference');return result;}
function instant(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,23)!==value.slice(0,23))throw new TypeError('Invalid period');return value;}
function command(kind,value){
 const common=['expected_revision','title','sections','citation_ids'],extra=kind==='report'?['type','period_start','period_end','timezone','learning_task']:['category','entity_id'];closed(value,[...common,...extra]);
 const citationIds=references(value.citation_ids);if(!Array.isArray(value.sections)||value.sections.length>100)throw new TypeError('Invalid sections');
 const result={expected_revision:number(value.expected_revision),title:text(value.title,512),sections:value.sections.map(section=>{closed(section,['heading','text','citation_ids']);const refs=references(section.citation_ids);if(refs.some(id=>!citationIds.includes(id)))throw new TypeError('Unknown section reference');if(typeof section.text!=='string'||[...section.text].length>200000)throw new TypeError('Invalid section text');return {heading:text(section.heading,512),text:section.text,citation_ids:refs};}),citation_ids:citationIds};
 if(kind==='report'){
  result.type=one(value.type,types);result.period_start=instant(value.period_start);result.period_end=instant(value.period_end);if(result.period_start>=result.period_end)throw new TypeError('Invalid period order');result.timezone=one(value.timezone,['Asia/Shanghai']);
  if(value.learning_task===null)result.learning_task=null;else{if(result.type!=='daily')throw new TypeError('Only daily report has learning task');const task=value.learning_task;closed(task,['package_id','object_kind','object_id','title','reason']);result.learning_task={package_id:identifier(task.package_id),object_kind:one(task.object_kind,['node','question','project']),object_id:identifier(task.object_id),title:text(task.title,512),reason:text(task.reason,2048)};}
 }else{if(value.category!==kind)throw new TypeError('Content category mismatch');result.category=kind;result.entity_id=identifier(value.entity_id);}
 if(new TextEncoder().encode(JSON.stringify(result)).length>262144)throw new TypeError('Content command exceeds transport limit');return result;
}


function versionTarget(value){closed(value,['kind','content_kind','content_id','revision','body_hash','input_job_id']);if(value.kind!=='content_version'||typeof value.body_hash!=='string'||!/^[0-9a-f]{64}$/.test(value.body_hash))throw new TypeError('Invalid content version target');return {kind:'content_version',content_kind:one(value.content_kind,kinds),content_id:identifier(value.content_id),revision:number(value.revision),body_hash:value.body_hash,input_job_id:value.input_job_id===null?null:identifier(value.input_job_id)};}

export function createAdminContentApi(client){return Object.freeze({
 async supplement(identifierValue,{kind,fields,signal,idempotencyKey}={}){
  if(!kinds.includes(kind)||typeof idempotencyKey!=='string'||!/^[!-~]{1,128}$/.test(idempotencyKey))throw new TypeError('Explicit supplement identity/key required');const target=identifier(identifierValue);closed(fields,['expected_revision','reason']);
  const body={expected_revision:number(fields.expected_revision),action:'supplement_generate',reason:text(fields.reason,2048)};if(!body.reason.trim())throw new TypeError('Supplement reason required');
  const {data}=await client.request('POST','/admin/content/'+kind+'/'+encodeURIComponent(target)+'/actions',{signal,idempotencyKey,body}),result=actionResult(data);
  if(result.generation===null||result.generation<1||result.cutoff===null||result.coverage===null||!['job','content'].includes(result.resource.kind)||result.resource.packageId!==null||result.resource.kind==='content'&&result.resource.id!==target)invalid();return result;
 },
 async dispositions(identifierValue,{kind,target,page=1,page_size=20,signal}={}){
  if(!kinds.includes(kind)||!Number.isSafeInteger(page)||page<1||!Number.isSafeInteger(page_size)||page_size<1||page_size>50)throw new TypeError('Invalid version disposition page');const id=identifier(identifierValue),expected=versionTarget(target);if(expected.content_kind!==kind||expected.content_id!==id)throw new TypeError('Version target does not match path');
  const query={revision:expected.revision,body_hash:expected.body_hash,...(expected.input_job_id===null?{}:{input_job_id:expected.input_job_id}),page,page_size};
  const {data}=await client.request('GET','/admin/content/'+kind+'/'+encodeURIComponent(id)+'/version-dispositions',{signal,query});if(!data||data.page!==page||data.page_size!==page_size||!Array.isArray(data.items)||data.items.length>page_size||typeof data.effective_valid!=='boolean')invalid();const actual=versionTarget(data.target),total=number(data.total),validityRevision=number(data.validity_revision);if(JSON.stringify(actual)!==JSON.stringify(expected)||data.effective_valid!==(total===0))invalid();
  const seen=new Set(),items=data.items.map(value=>{if(!value)invalid();const id=identifier(value.id),reason=text(value.reason,2048);if(!reason.trim()||seen.has(id))invalid();seen.add(id);return {id,reason,createdAt:instant(value.created_at)};});if(items.length>Math.max(0,total-(page-1)*page_size))invalid();return freeze({target:actual,validityRevision,effectiveValid:data.effective_valid,items,page,pageSize:page_size,total});
 },
 async versions(identifierValue,{kind,page=1,page_size=20,signal}={}){
  if(!kinds.includes(kind)||!Number.isSafeInteger(page)||page<1||!Number.isSafeInteger(page_size)||page_size<1||page_size>50)throw new TypeError('Invalid referenced-version page');const target=identifier(identifierValue);
  const {data}=await client.request('GET','/admin/content/'+kind+'/'+encodeURIComponent(target)+'/referenced-versions',{signal,query:{page,page_size}});if(!data||data.page!==page||data.page_size!==page_size||!Array.isArray(data.items)||data.items.length>page_size)invalid();const total=number(data.total),seen=new Set();
  const items=data.items.map(value=>{if(!value||value.version_kind!=='body'||typeof value.effective_valid!=='boolean')invalid();const target=versionTarget(value.target),key=JSON.stringify(target);if(target.content_kind!==kind||target.content_id!==identifierValue)invalid();if(seen.has(key))invalid();seen.add(key);return {target,versionKind:'body',validityRevision:number(value.validity_revision),effectiveValid:value.effective_valid};});if(items.length>Math.max(0,total-(page-1)*page_size))invalid();return freeze({items,page,pageSize:page_size,total});
 },
 async versionAction(identifierValue,{kind,fields,signal,idempotencyKey}={}){
  if(!kinds.includes(kind)||typeof idempotencyKey!=='string'||!/^[!-~]{1,128}$/.test(idempotencyKey))throw new TypeError('Explicit version action identity/key required');const target=identifier(identifierValue);if(!fields||!['invalidate_conclusion','reverse_invalidation'].includes(fields.action))throw new TypeError('Invalid version action');closed(fields,['expected_validity_revision','action','reason','target',...(fields.action==='reverse_invalidation'?['invalidation_id']:[])]);
  const body={expected_validity_revision:number(fields.expected_validity_revision),action:fields.action,reason:text(fields.reason,2048),target:versionTarget(fields.target)};if(body.target.content_kind!==kind||body.target.content_id!==target)throw new TypeError('Version target does not match path');if(!body.reason.trim())throw new TypeError('Disposition reason required');if(fields.action==='reverse_invalidation')body.invalidation_id=identifier(fields.invalidation_id);
  const {data}=await client.request('POST','/admin/content/'+kind+'/'+encodeURIComponent(target)+'/version-actions',{signal,idempotencyKey,body});return actionResult(data);
 },
 async action(identifierValue,{kind,fields,signal,idempotencyKey}={}){
  if(!kinds.includes(kind)||typeof idempotencyKey!=='string'||!/^[!-~]{1,128}$/.test(idempotencyKey))throw new TypeError('Explicit content identity/key required');const target=identifier(identifierValue);
  if(!fields||!['approve','reject','publish','withdraw'].includes(fields.action))throw new TypeError('Invalid publication action');closed(fields,['expected_revision','action','reason',...(fields.action==='withdraw'?['withdrawal_reason']:[])]);
  const body={expected_revision:number(fields.expected_revision),action:fields.action,reason:text(fields.reason,2048)};if(!body.reason.trim())throw new TypeError('Action explanation required');if(fields.action==='withdraw')body.withdrawal_reason=one(fields.withdrawal_reason,['display_adjustment','conclusion_error']);
  const {data}=await client.request('POST','/admin/content/'+kind+'/'+encodeURIComponent(target)+'/actions',{signal,idempotencyKey,body}),result=actionResult(data);
  if(result.resource.kind!=='content'||result.resource.id!==target||result.resource.packageId!==null)invalid();return result;
 },
 async save(identifierValue,{kind,fields,signal,idempotencyKey}={}){
  if(!kinds.includes(kind)||typeof idempotencyKey!=='string'||!/^[!-~]{1,128}$/.test(idempotencyKey))throw new TypeError('Explicit content identity/key required');const target=identifier(identifierValue),body=command(kind,fields);
  const {data}=await client.request('PUT','/admin/content/'+kind+'/'+encodeURIComponent(target),{signal,idempotencyKey,body});
  const value=kind==='report'?reportDetail(data,target,{draft:true}):interpretationDetail(data,target,kind,{draft:true});return freeze({...value,kind});
 },
 async detail(identifier,{kind,signal}={}){
  if(!kinds.includes(kind))throw new TypeError('Invalid owner content kind');
  const value=kind==='report'?await createReportsApi(client).detail(identifier,{signal,draft:true}):await createInterpretationsApi(client).detail(identifier,{kind,signal,draft:true});
  return freeze({...value,kind});
 },
 async list({kind,state,type,page=1,page_size=20,signal}={}){
  if(kind!=null&&!kinds.includes(kind)||state!=null&&!states.includes(state)||type!=null&&(!types.includes(type)||kind!=null&&kind!=='report')||!Number.isSafeInteger(page)||page<1||!Number.isSafeInteger(page_size)||page_size<1||page_size>50)throw new TypeError('Invalid owner catalog filters');
  const {data}=await client.request('GET','/admin/content',{signal,query:{kind,state,type,page,page_size}});
  if(!data||data.page!==page||data.page_size!==page_size||!Array.isArray(data.items)||data.items.length>page_size)invalid();const total=number(data.total),seen=new Set();
  const items=data.items.map(value=>{if(!value)invalid();const item={id:text(value.id,64),kind:one(value.kind,kinds),title:text(value.title,512),revision:number(value.revision),bodyRevision:value.body_revision===null?null:number(value.body_revision),publicationState:one(value.publication_state,states),reportType:value.report_type===null?null:one(value.report_type,types)};
   if(/[\u0000-\u001f\u007f]/.test(item.id)||item.kind!=='report'&&item.reportType!==null||kind!=null&&item.kind!==kind||state!=null&&item.publicationState!==state||type!=null&&(item.kind!=='report'||item.reportType!==type))invalid();const key=item.kind+'\0'+item.id;if(seen.has(key))invalid();seen.add(key);return item;
  });
  if(items.length>Math.max(0,total-(page-1)*page_size))invalid();return freeze({items,page,pageSize:page_size,total});
 }
});}
