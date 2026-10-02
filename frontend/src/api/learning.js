import {ApiError} from './client.js';
const fail=()=>{throw new TypeError('Invalid learning wire');};
function closed(v,keys){if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))fail();}
function options(v,keys){if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).some(k=>!keys.includes(k)))fail();}
function text(v,max,empty=false){if(typeof v!=='string'||!empty&&![...v].length||[...v].length>max||[...v].some(c=>c.length===1&&/[\ud800-\udfff]/.test(c)))fail();return v;}
function id(v){text(v,64);if(/[\u0000-\u001f\u007f-\u009f]/.test(v))fail();return v;}
function number(v,min=0,max=Number.MAX_SAFE_INTEGER){if(!Number.isSafeInteger(v)||v<min||v>max)fail();return v;}
function unique(v,max,parse,key=x=>x){if(!Array.isArray(v)||v.length>max)fail();const out=v.map(parse);if(new Set(out.map(key)).size!==out.length)fail();return Object.freeze(out);}
function pkg(v){closed(v,['id','title','description','revision','state']);if(v.state!=='published')fail();return Object.freeze({id:id(v.id),title:text(v.title,200),description:text(v.description,16384,true),revision:number(v.revision),state:v.state});}
function route(v,packageId){closed(v,['package_id','id','title','module_ids','completed_node_count','eligible_node_count','completion_rate']);if(id(v.package_id)!==packageId)fail();const completedNodeCount=number(v.completed_node_count),eligibleNodeCount=number(v.eligible_node_count),rate=v.completion_rate;if(completedNodeCount>eligibleNodeCount||rate!==null&&(typeof rate!=='string'||!/^(0(\.[0-9]{1,8})?|1(\.0{1,8})?)$/.test(rate)||rate.endsWith('\n')))fail();return Object.freeze({packageId,id:id(v.id),title:text(v.title,200),moduleIds:unique(v.module_ids,1000,id),completedNodeCount,eligibleNodeCount,completionRate:rate});}
function response(v,p){try{if(new TextEncoder().encode(JSON.stringify(v)).length>1048576)fail();return p(v);}catch{throw new ApiError(200,'invalid_response','Learning response cannot be verified');}}
function paging(o){return {page:number(o.page??1,1),page_size:number(o.pageSize??20,1,50)};}
function page(v,q,parse){closed(v,['items','page','page_size','total']);if(number(v.page,1)!==q.page||number(v.page_size,1,50)!==q.page_size)fail();const items=unique(v.items,q.page_size,parse,x=>x.id),total=number(v.total);if(total<items.length)fail();return Object.freeze({items,page:q.page,pageSize:q.page_size,total});}
export function createLearningApi(client){return Object.freeze({
 async packages(o={}){options(o,['page','pageSize','signal']);const query=paging(o),{data}=await client.request('GET','/learning/packages',{query,signal:o.signal});return response(data,x=>page(x,query,pkg));},
 async package(packageId,o={}){id(packageId);options(o,['signal']);const {data}=await client.request('GET','/learning/packages/'+encodeURIComponent(packageId),{signal:o.signal});return response(data,x=>{const p=pkg(x);if(p.id!==packageId)fail();return p;});},
 async routes(packageId,o={}){id(packageId);options(o,['page','pageSize','routeId','moduleId','q','signal']);const query=paging(o);for(const [k,wire]of [['routeId','route_id'],['moduleId','module_id'],['q','q']])if(o[k]!==undefined)query[wire]=k==='q'?text(o[k],256):id(o[k]);const {data}=await client.request('GET','/learning/packages/'+encodeURIComponent(packageId)+'/routes',{query,signal:o.signal});return response(data,x=>page(x,query,r=>route(r,packageId)));}
});}
