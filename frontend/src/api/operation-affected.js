import {ApiError} from './client.js';
const invalid=()=>{throw new ApiError(200,'invalid_response','Operation impact is unavailable');};
function text(value,max){if(typeof value!=='string'||!value||[...value].length>max||/[\u0000-\u001f\u007f]/.test(value)||!value.isWellFormed())invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
export function createOperationAffectedApi(client){return Object.freeze({
 async read(operationRef,options={}){
  if(!options||typeof options!=='object'||Array.isArray(options)||Object.keys(options).some(key=>!['cursor','limit','signal'].includes(key)))throw new TypeError('Invalid impact query');
  const {cursor=null,limit=20,signal}=options;text(operationRef,64);if(cursor!==null)text(cursor,512);if(!Number.isSafeInteger(limit)||limit<1||limit>50)throw new TypeError('Invalid impact page limit');
  const {data}=await client.request('GET','/admin/operations/'+encodeURIComponent(operationRef)+'/affected',{signal,query:{limit,...(cursor===null?{}:{cursor})}});
  if(!data||data.operation_ref!==operationRef||!['pending','complete'].includes(data.state)||!Array.isArray(data.items)||data.items.length>limit)invalid();
  const seen=new Set(),items=data.items.map(ref=>{if(!ref)invalid();const value=Object.freeze({kind:text(ref.kind,32),id:text(ref.id,64),packageId:ref.package_id===null?null:text(ref.package_id,64)}),identity=JSON.stringify([value.kind,value.id,value.packageId]);if(seen.has(identity))invalid();seen.add(identity);return value;});
  const nextCursor=data.next_cursor===null?null:text(data.next_cursor,512);let total;
  if(data.state==='pending'){if(data.total!==null||items.length||nextCursor!==null||cursor!==null)invalid();total=null;}
  else{total=number(data.total);if(total<items.length||total>0&&!items.length||total===0&&nextCursor!==null||nextCursor!==null&&(items.length!==limit||total<=items.length||nextCursor===cursor)||cursor===null&&nextCursor===null&&total!==items.length)invalid();}
  return Object.freeze({operationRef,state:data.state,total,items:Object.freeze(items),nextCursor,cursor,limit});
 }
});}
