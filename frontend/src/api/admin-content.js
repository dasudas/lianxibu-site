import {ApiError} from './client.js';
const kinds=['report','model','agent','skill','workflow'],states=['draft','approved','published','withdrawn'],types=['daily','weekly','monthly'];
const invalid=()=>{throw new ApiError(200,'invalid_response','Content catalog is unavailable');};
const freeze=value=>{if(value&&typeof value==='object'){for(const child of Object.values(value))freeze(child);Object.freeze(value);}return value;};
function text(value,max){if(typeof value!=='string'||!value||[...value].length>max)invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function one(value,choices){if(!choices.includes(value))invalid();return value;}
export function createAdminContentApi(client){return Object.freeze({
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
