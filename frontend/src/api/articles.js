import { ApiError } from './client.js';
import { recordProjection } from './records.js';
const invalid=()=>{throw new ApiError(200,'invalid_response','Article data is unavailable');};
function text(value,max,empty=false){if(typeof value!=='string'||(!empty&&!value)||[...value].length>max)invalid();return value;}
function id(value,max=64){text(value,max);if(/[\u0000-\u001f\u007f]/.test(value))invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function nullable(value,parse){return value===null?null:parse(value);}
function instant(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,23)!==value.slice(0,23))invalid();return value;}
function url(value){text(value,2000);if(!value.startsWith('https://')||/[\u0000-\u0020\u007f\\]/.test(value))invalid();let result;try{result=new URL(value);}catch{invalid();}if(!result.hostname||result.username||result.password)invalid();return value;}
function article(value){
  if(!value)invalid();const key=id(value.id),record=value.record===null?null:recordProjection(value.record);
  if(record&&(record.kind!=='article'||record.id!==key||record.packageId!==null))invalid();
  return Object.freeze({id:key,title:text(value.title,512),summary:nullable(value.summary,v=>text(v,16384,true)),sourceId:id(value.source_id,80),sourceName:text(value.source_name,512),sourceUrl:url(value.source_url),publishedAt:nullable(value.published_at,instant),observedAt:instant(value.observed_at),topic:nullable(value.topic,v=>text(v,128)),region:nullable(value.region,v=>text(v,128)),contentStatus:text(value.content_status,32),revision:number(value.revision),record,href:`/app/articles/${encodeURIComponent(key)}`});
}
export function createArticlesApi(client){return Object.freeze({
  async list({q=null,source_id=null,status=null,page=1,page_size=20,signal}={}){
    if(q!==null)text(q,256);if(source_id!==null)id(source_id,80);if(status!==null&&!['not_started','planned','learning','completed'].includes(status))invalid();
    if(!Number.isSafeInteger(page)||page<1||!Number.isSafeInteger(page_size)||page_size<1||page_size>50)invalid();
    const {data}=await client.request('GET','/private/articles',{signal,query:{q,source_id,status,page,page_size}});
    if(!data||data.page!==page||data.page_size!==page_size||!Array.isArray(data.items)||data.items.length>page_size)invalid();const total=number(data.total),seen=new Set();
    const items=data.items.map(value=>{const item=article(value);if(seen.has(item.id))invalid();seen.add(item.id);return item;});
    if(items.length>Math.max(0,total-(page-1)*page_size))invalid();return Object.freeze({items:Object.freeze(items),page,pageSize:page_size,total});
  },
  async detail(identifier,{signal}={}){id(identifier);const {data}=await client.request('GET',`/private/articles/${encodeURIComponent(identifier)}`,{signal});const item=article(data);if(item.id!==identifier)invalid();return item;}
});}
