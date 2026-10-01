import {ApiError} from './client.js';
const invalid=()=>{throw new ApiError(200,'invalid_response','Action receipt is unavailable');};
const freeze=value=>{if(value&&typeof value==='object'){for(const child of Object.values(value))freeze(child);Object.freeze(value);}return value;};
function text(value,max){if(typeof value!=='string'||!value||[...value].length>max)invalid();return value;}
function id(value){text(value,64);if(/[\u0000-\u001f\u007f]/.test(value))invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function nullable(value,parse){return value===null?null:parse(value);}
function list(value,max){if(!Array.isArray(value)||value.length>max)invalid();return value;}
function instant(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,23)!==value.slice(0,23))invalid();return value;}
function date(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value)invalid();return value;}
function ref(value){if(!value)invalid();return {kind:text(value.kind,32),id:id(value.id),packageId:nullable(value.package_id,id)};}
function coverage(value){if(!value||!['complete','partial','no_input'].includes(value.state))invalid();return {state:value.state,missingInputs:list(value.missing_inputs,1000).map(v=>{if(!v)invalid();return {kind:text(v.kind,32),id:nullable(v.id,id),businessDate:nullable(v.business_date,date),reason:text(v.reason,256)};}),missingDates:list(value.missing_dates,366).map(date),noUpdateDates:list(value.no_update_dates,366).map(date)};}
export function actionResult(value){if(!value)invalid();return freeze({operationRef:id(value.operation_ref),resource:ref(value.result_resource_ref),resultRevision:number(value.result_revision),generation:nullable(value.generation,number),cutoff:nullable(value.cutoff,instant),coverage:nullable(value.coverage,coverage),affected:list(value.affected,50).map(ref),nextCursor:nullable(value.next_cursor,v=>text(v,512))});}
