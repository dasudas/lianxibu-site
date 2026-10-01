import {ApiError} from './client.js';
import {actionResult} from './action-result.js';
const invalid=()=>{throw new ApiError(200,'invalid_response','Result action receipt cannot be verified');};
function id(value){if(typeof value!=='string'||!value.trim()||[...value].length>64||!value.isWellFormed()||/[\u0000-\u001f\u007f]/.test(value))throw new TypeError('Invalid logical task identity');return value;}
function revision(value){if(!Number.isSafeInteger(value)||value<0)throw new TypeError('Invalid result action revision');return value;}
function command(fields){
 if(!fields||typeof fields!=='object'||Array.isArray(fields)||!['apply_result','discard_result'].includes(fields.action))throw new TypeError('Invalid retained result action');
 const keys=['action','expected_revision','reason',...(fields.action==='apply_result'?['expected_content_revision','result_hash']:[])];
 if(Object.keys(fields).length!==keys.length||keys.some(k=>!Object.hasOwn(fields,k)))throw new TypeError('Invalid result action fields');
 if(typeof fields.reason!=='string'||!fields.reason.trim()||[...fields.reason].length>2048||!fields.reason.isWellFormed())throw new TypeError('Explicit result action explanation required');
 const body={action:fields.action,expected_revision:revision(fields.expected_revision),reason:fields.reason};
 if(fields.action==='apply_result'){body.expected_content_revision=revision(fields.expected_content_revision);if(typeof fields.result_hash!=='string'||!/^[0-9a-f]{64}$/.test(fields.result_hash))throw new TypeError('Confirmed retained result hash required');body.result_hash=fields.result_hash;}
 return Object.freeze(body);
}
export function createJobResultActionsApi(client){return Object.freeze({async submit(identifier,options={}){
 const target=id(identifier);if(!options||typeof options!=='object'||Array.isArray(options)||Object.keys(options).some(k=>!['fields','idempotencyKey','signal'].includes(k)))throw new TypeError('Invalid result action options');
 const {fields,idempotencyKey,signal}=options;if(typeof idempotencyKey!=='string'||!/^[!-~]{1,128}$/.test(idempotencyKey))throw new TypeError('Stable result action key required');
 const body=command(fields),{data}=await client.request('POST','/admin/jobs/'+encodeURIComponent(target)+'/actions',{body,idempotencyKey,signal});
 const result=actionResult(data);
 try{ id(result.operationRef);for(const ref of [result.resource,...result.affected]){id(ref.id);if(ref.packageId!==null)id(ref.packageId);if(!ref.kind.isWellFormed())invalid();} }catch{invalid();}
 if(!['job','content'].includes(result.resource.kind)||result.resource.packageId!==null||result.resource.kind==='job'&&result.resource.id!==target)invalid();
 return result;
}});}
