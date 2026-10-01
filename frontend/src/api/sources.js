import {ApiError} from './client.js';
const fail=()=>{throw new TypeError('Invalid source protocol');};
function closed(v,fields){if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==fields.length||fields.some(k=>!Object.hasOwn(v,k)))fail();}
function text(v,min,max){if(typeof v!=='string'||[...v].length<min||[...v].length>max||[...v].some(c=>c.length===1&&/[\ud800-\udfff]/.test(c)))fail();return v;}
function integer(v,min=0,max=Number.MAX_SAFE_INTEGER){if(!Number.isSafeInteger(v)||v<min||v>max)fail();return v;}
function one(v,values){if(!values.includes(v))fail();return v;}
function identity(v){text(v,1,80);if(/[\u0000-\u001f\u007f]/.test(v))fail();return v;}
const nullable=(v,parse)=>v===null?null:parse(v);
function time(v){text(v,27,27);if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(v))fail();const parsed=new Date(v);if(!Number.isFinite(parsed.getTime())||parsed.toISOString().slice(0,23)!==v.slice(0,23))fail();return v;}
function array(v,min,max,parse){if(!Array.isArray(v)||v.length<min||v.length>max)fail();return Object.freeze(v.map(parse));}
function url(v){text(v,1,2000);if(!v.startsWith('https://'))fail();try{const u=new URL(v);if(u.protocol!=='https:'||!u.hostname)fail();}catch{fail();}return v;}
function config(v){closed(v,['adapter','entry_urls','identity_rule','window_rule','parser_version','max_items','max_response_bytes','allowed_hosts','valid_until','auth_secret_ref']);return Object.freeze({adapter:text(v.adapter,1,64),entryUrls:array(v.entry_urls,1,100,url),identityRule:text(v.identity_rule,1,128),windowRule:text(v.window_rule,1,128),parserVersion:text(v.parser_version,1,64),maxItems:integer(v.max_items,1,100000),maxResponseBytes:integer(v.max_response_bytes,1,10485760),allowedHosts:array(v.allowed_hosts,1,100,x=>text(x,1,253)),validUntil:time(v.valid_until),authSecretRef:nullable(v.auth_secret_ref,x=>text(x,1,128))});}
function source(v){closed(v,['id','name','category','revision','config_revision','activation_stage','draft_config','effective_config','config_valid_until']);return Object.freeze({id:identity(v.id),name:text(v.name,1,200),category:one(v.category,['news_tech','agent','skill','workflow','model']),revision:integer(v.revision),configRevision:integer(v.config_revision),activationStage:one(v.activation_stage,['disabled','trial','production']),draftConfig:nullable(v.draft_config,config),effectiveConfig:nullable(v.effective_config,config),configValidUntil:nullable(v.config_valid_until,time)});}
export function createSourcesApi(client){return Object.freeze({async list(opts={}){
 if(!opts||typeof opts!=='object'||Array.isArray(opts)||Object.keys(opts).some(k=>!['page','pageSize','signal'].includes(k)))fail();
 const page=integer(opts.page??1,1),pageSize=integer(opts.pageSize??20,1,50);
 const {data}=await client.request('GET','/admin/sources',{query:{page,page_size:pageSize},signal:opts.signal});
 try{closed(data,['items','page','page_size','total']);if(new TextEncoder().encode(JSON.stringify(data)).length>1048576)fail();if(integer(data.page,1)!==page||integer(data.page_size,1,50)!==pageSize)fail();const items=array(data.items,0,pageSize,source),total=integer(data.total);if(total<items.length||new Set(items.map(x=>x.id)).size!==items.length)fail();return Object.freeze({items,page,pageSize,total});}catch{throw new ApiError(200,'invalid_response');}
}});}
