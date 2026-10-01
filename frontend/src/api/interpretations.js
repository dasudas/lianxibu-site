import { ApiError } from './client.js';
const routes={model:'models',agent:'agents',skill:'skills',workflow:'workflows'};
const invalid=()=>{throw new ApiError(200,'invalid_response','Interpretation data is unavailable');};
const freeze=value=>{if(value&&typeof value==='object'){for(const child of Object.values(value))freeze(child);Object.freeze(value);}return value;};
function text(value,max,empty=false){if(typeof value!=='string'||!empty&&!value||[...value].length>max)invalid();return value;}
function id(value){text(value,64);if(/[\u0000-\u001f\u007f]/.test(value))invalid();return value;}
function number(value){if(!Number.isSafeInteger(value)||value<0)invalid();return value;}
function array(value,max){if(!Array.isArray(value)||value.length>max)invalid();return value;}
function flag(value){if(typeof value!=='boolean')invalid();return value;}
function nullable(value,parse){return value===null?null:parse(value);}
function one(value,values){if(!values.includes(value))invalid();return value;}
function instant(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,23)!==value.slice(0,23))invalid();return value;}
function date(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value)invalid();return value;}
function metric(value){if(!value)invalid();return {benchmark:text(value.benchmark,128),benchmarkVersion:text(value.benchmark_version,256),task:text(value.task,256),score:nullable(value.score,value=>{text(value,30);if(!/^-?(0|[1-9]\d*)(\.\d{1,8})?$/.test(value))invalid();return value;}),unit:one(value.unit,['percent','ratio','points','seconds','count']),evaluatedAt:nullable(value.evaluated_at,instant),higherIsBetter:flag(value.higher_is_better),evaluationScope:one(value.evaluation_scope,['entity','configuration']),provenance:one(value.provenance,['author_self_report','third_party'])};}
export function interpretationSummary(value,kind){
    if(!value||!Object.hasOwn(routes,kind)||value.kind!==kind||value.publication_state!=='published'||flag(value.review_required))invalid();
    if(array(value.blocking_reasons,100).length)invalid();const coverage=value.coverage;if(!coverage)invalid();
    return freeze({id:id(value.id),kind,title:text(value.title,512),revision:number(value.revision),bodyRevision:number(value.body_revision),publicationState:value.publication_state,publishedAt:nullable(value.published_at,instant),inputSnapshotId:nullable(value.input_snapshot_id,id),generation:nullable(value.generation,number),inputCutoffAt:nullable(value.input_cutoff_at,instant),reviewRequired:false,blockingReasons:[],corrected:flag(value.affected_by_correction),supersedesJobId:nullable(value.supersedes_job_id,id),coverage:{state:one(coverage.state,['complete','partial','no_input']),missingInputs:array(coverage.missing_inputs,1000).map(item=>{if(!item)invalid();return{kind:text(item.kind,32),id:nullable(item.id,id),businessDate:nullable(item.business_date,date),reason:text(item.reason,256)};}),missingDates:array(coverage.missing_dates,366).map(date),noUpdateDates:array(coverage.no_update_dates,366).map(date)},name:text(value.name,512),provider:nullable(value.provider,v=>text(v,256)),tags:array(value.tags,100).map(v=>text(v,128)),description:nullable(value.description,v=>text(v,16384,true)),evidenceTime:nullable(value.evidence_time,instant),metrics:array(value.metrics,100).map(metric),stars:nullable(value.stars,number),starsGrowth:nullable(value.stars_growth,number),href:`/${routes[kind]}/${encodeURIComponent(value.id)}`});
}
export function createInterpretationsApi(client){return Object.freeze({
    async list({kind,page=1,page_size=20,signal}={}){
        if(!Object.hasOwn(routes,kind)||!Number.isSafeInteger(page)||page<1||!Number.isSafeInteger(page_size)||page_size<1||page_size>50)throw new TypeError('Invalid interpretation page');
        const {data}=await client.request('GET','/interpretations',{signal,query:{kind,page,page_size}});
        if(!data||data.page!==page||data.page_size!==page_size)invalid();const total=number(data.total),seen=new Set();
        const items=array(data.items,page_size).map(value=>{const item=interpretationSummary(value,kind);if(seen.has(item.id))invalid();seen.add(item.id);return item;});
        if(items.length>Math.max(0,total-(page-1)*page_size))invalid();return freeze({kind,items,page,pageSize:page_size,total});
    }
});}
