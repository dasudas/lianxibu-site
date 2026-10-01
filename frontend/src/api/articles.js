import { ApiError } from './client.js';
import { recordProjection } from './records.js';
const invalid=()=>{throw new ApiError(200,'invalid_response','Article data is unavailable');};
function text(value,max,empty=false){if(typeof value!=='string'||(!empty&&!value)||[...value].length>max)invalid();return value;}
function id(value,max=64){text(value,max);if(/[\u0000-\u001f\u007f]/.test(value))invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function nullable(value,parse){return value===null?null:parse(value);}
function instant(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,23)!==value.slice(0,23))invalid();return value;}
function url(value){text(value,2000);if(!value.startsWith('https://')||/[\u0000-\u0020\u007f\\]/.test(value))invalid();let result;try{result=new URL(value);}catch{invalid();}if(!result.hostname||result.username||result.password)invalid();return value;}
function date(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value)invalid();return value;}
function entitySummary(value){
 if(value===null)return null;if(!value||value.category!=='news_tech')invalid();const result={id:id(value.id),category:'news_tech',firstRecordedAt:nullable(value.first_recorded_at,instant),firstSeenBusinessDate:nullable(value.first_seen_business_date,date),lastSeenAt:nullable(value.last_seen_at,instant),occurrenceCount:nullable(value.occurrence_count,number),repeatCount:nullable(value.repeat_count,number),analysisState:value.analysis_state,revision:number(value.revision)};
 if(result.analysisState!==null&&!['pending','queued','analyzed','failed','needs_review'].includes(result.analysisState)||(result.occurrenceCount===null)!==(result.repeatCount===null)||result.occurrenceCount!==null&&result.repeatCount!==Math.max(result.occurrenceCount-1,0)||result.firstRecordedAt!==null&&result.lastSeenAt!==null&&result.firstRecordedAt>result.lastSeenAt)invalid();return Object.freeze(result);
}
function article(value,{includeEntity=false}={}){
  if(!value)invalid();const key=id(value.id),record=value.record===null?null:recordProjection(value.record);
  if(record&&(record.kind!=='article'||record.id!==key||record.packageId!==null))invalid();
  return Object.freeze({id:key,title:text(value.title,512),summary:nullable(value.summary,v=>text(v,16384,true)),sourceId:id(value.source_id,80),sourceName:text(value.source_name,512),sourceUrl:url(value.source_url),publishedAt:nullable(value.published_at,instant),observedAt:instant(value.observed_at),topic:nullable(value.topic,v=>text(v,128)),region:nullable(value.region,v=>text(v,128)),contentStatus:text(value.content_status,32),revision:number(value.revision),record,...(includeEntity?{entitySummary:entitySummary(value.entity_summary)}:{}),href:`/app/articles/${encodeURIComponent(key)}`});
}
export function createArticlesApi(client){return Object.freeze({
  async list({include_entity=false,q=null,source_id=null,status=null,page=1,page_size=20,signal}={}){
    if(typeof include_entity!=='boolean')invalid();if(q!==null)text(q,256);if(source_id!==null)id(source_id,80);if(status!==null&&!['not_started','planned','learning','completed'].includes(status))invalid();
    if(!Number.isSafeInteger(page)||page<1||!Number.isSafeInteger(page_size)||page_size<1||page_size>50)invalid();
    const {data}=await client.request('GET','/private/articles',{signal,query:{q,source_id,status,page,page_size,...(include_entity?{include_entity:true}:{})}});
    if(!data||data.page!==page||data.page_size!==page_size||!Array.isArray(data.items)||data.items.length>page_size)invalid();const total=number(data.total),seen=new Set();
    const items=data.items.map(value=>{const item=article(value,{includeEntity:include_entity});if(seen.has(item.id))invalid();seen.add(item.id);return item;});
    if(items.length>Math.max(0,total-(page-1)*page_size))invalid();return Object.freeze({items:Object.freeze(items),page,pageSize:page_size,total});
  },
  async detail(identifier,{signal,include_entity=false}={}){if(typeof include_entity!=='boolean')invalid();id(identifier);const {data}=await client.request('GET',`/private/articles/${encodeURIComponent(identifier)}`,{signal,...(include_entity?{query:{include_entity:true}}:{})});const item=article(data,{includeEntity:include_entity});if(item.id!==identifier)invalid();return item;}
});}
