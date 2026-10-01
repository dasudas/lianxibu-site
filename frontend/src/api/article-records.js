import { ApiError } from './client.js';
import { recordProjection } from './records.js';
const statuses=['not_started','planned','learning','completed'];
const invalid=()=>{throw new ApiError(200,'invalid_response','Article records are unavailable');};
function count(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function text(value,max){if(typeof value!=='string'||!value||[...value].length>max)invalid();return value;}
function info(value,identity){
    if(value===null)return null;
    if(!value||value.id!==identity||/[\u0000-\u001f\u007f]/.test(text(value.id,64)))invalid();
    return Object.freeze({id:identity,title:text(value.title,512),sourceName:value.source_name===null?null:text(value.source_name,512),href:`/app/articles/${encodeURIComponent(identity)}`});
}
export function createArticleRecordsApi(client){return Object.freeze({
    async list({q=null,status=null,favorite=null,page=1,page_size=20,signal}={}){
        if(q!==null)text(q,256);if(status!==null&&!statuses.includes(status)||favorite!==null&&typeof favorite!=='boolean')invalid();
        if(!Number.isSafeInteger(page)||page<1||!Number.isSafeInteger(page_size)||page_size<1||page_size>50)invalid();
        const {data}=await client.request('GET','/private/article-records',{signal,query:{q,status,favorite,page,page_size}});
        if(!data||data.page!==page||data.page_size!==page_size||!Array.isArray(data.items)||data.items.length>page_size||!data.stats)invalid();
        const stats=Object.freeze(Object.fromEntries(['total','favorite','planned','learning','completed'].map(key=>[key,count(data.stats[key])]))),total=count(data.total);
        if(total>stats.total||stats.favorite>stats.total||stats.planned>stats.total-stats.learning||stats.planned+stats.learning>stats.total-stats.completed||data.items.length>Math.max(0,total-(page-1)*page_size))invalid();
        const seen=new Set(),items=data.items.map(value=>{if(!value)invalid();const record=recordProjection(value.record);if(record.kind!=='article'||record.packageId!==null||seen.has(record.id))invalid();seen.add(record.id);return Object.freeze({record,article:info(value.article,record.id)});});
        return Object.freeze({items:Object.freeze(items),page,pageSize:page_size,total,stats});
    }
});}
