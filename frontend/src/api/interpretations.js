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
export function interpretationSummary(value,kind,{draft=false}={}){
    if(typeof draft!=='boolean'||!value||!Object.hasOwn(routes,kind)||value.kind!==kind)invalid();
    const publicationState=one(value.publication_state,['draft','approved','published','withdrawn']),reviewRequired=flag(value.review_required),blockingReasons=array(value.blocking_reasons,100).map(reason=>text(reason,256));
    if(!draft&&(publicationState!=='published'||reviewRequired||blockingReasons.length))invalid();const coverage=value.coverage;if(!coverage)invalid();
    return freeze({id:id(value.id),kind,title:text(value.title,512),revision:number(value.revision),bodyRevision:number(value.body_revision),publicationState:value.publication_state,publishedAt:nullable(value.published_at,instant),inputSnapshotId:nullable(value.input_snapshot_id,id),generation:nullable(value.generation,number),inputCutoffAt:nullable(value.input_cutoff_at,instant),reviewRequired,blockingReasons,corrected:flag(value.affected_by_correction),supersedesJobId:nullable(value.supersedes_job_id,id),coverage:{state:one(coverage.state,['complete','partial','no_input']),missingInputs:array(coverage.missing_inputs,1000).map(item=>{if(!item)invalid();return{kind:text(item.kind,32),id:nullable(item.id,id),businessDate:nullable(item.business_date,date),reason:text(item.reason,256)};}),missingDates:array(coverage.missing_dates,366).map(date),noUpdateDates:array(coverage.no_update_dates,366).map(date)},name:text(value.name,512),provider:nullable(value.provider,v=>text(v,256)),tags:array(value.tags,100).map(v=>text(v,128)),description:nullable(value.description,v=>text(v,16384,true)),evidenceTime:nullable(value.evidence_time,instant),metrics:array(value.metrics,100).map(metric),stars:nullable(value.stars,number),starsGrowth:nullable(value.stars_growth,number),href:`/${routes[kind]}/${encodeURIComponent(value.id)}`});
}
function sourceUrl(value){
    if(value===null)return null;text(value,2000);if(!value.startsWith('https://')||/[\u0000-\u0020\u007f\\]/.test(value))invalid();
    let url;try{url=new URL(value);}catch{invalid();}if(url.protocol!=='https:'||!url.hostname||url.username||url.password)invalid();return value;
}
function citation(value){if(!value)invalid();return {id:id(value.id),kind:one(value.kind,['article_revision','catalog_evidence','content_version']),title:text(value.title,512),sourceUrl:sourceUrl(value.source_url),revision:nullable(value.revision,number),bodyHash:nullable(value.body_hash,hash=>{if(typeof hash!=='string'||!/^[0-9a-f]{64}$/.test(hash))invalid();return hash;})};}
export function createInterpretationsApi(client){return Object.freeze({
    async detail(identifier,{kind,signal,draft=false}={}){
        if(!Object.hasOwn(routes,kind)||typeof draft!=='boolean')throw new TypeError('Invalid interpretation kind or owner mode');id(identifier);
        const {data}=await client.request('GET',(draft?'/admin/content/'+kind+'/':'/interpretations/')+encodeURIComponent(identifier),{signal});
        return interpretationDetail(data,identifier,kind,{draft});
    },
    async list({kind,page=1,page_size=20,signal}={}){
        if(!Object.hasOwn(routes,kind)||!Number.isSafeInteger(page)||page<1||!Number.isSafeInteger(page_size)||page_size<1||page_size>50)throw new TypeError('Invalid interpretation page');
        const {data}=await client.request('GET','/interpretations',{signal,query:{kind,page,page_size}});
        if(!data||data.page!==page||data.page_size!==page_size)invalid();const total=number(data.total),seen=new Set();
        const items=array(data.items,page_size).map(value=>{const item=interpretationSummary(value,kind);if(seen.has(item.id))invalid();seen.add(item.id);return item;});
        if(items.length>Math.max(0,total-(page-1)*page_size))invalid();return freeze({kind,items,page,pageSize:page_size,total});
    }
});}

export function interpretationDetail(data,identifier,kind,{draft=false}={}) {
        const summary=interpretationSummary(data,kind,{draft});if(summary.id!==identifier)invalid();const body=data.body;if(!body||body.category!==kind)invalid();
        const citations=array(data.citations,1000).map(citation),known=new Set(citations.map(item=>item.id));if(known.size!==citations.length)invalid();
        const references=values=>array(values,1000).map(value=>{const key=id(value);if(!known.has(key))invalid();return key;});
        const citationIds=references(body.citation_ids),sections=array(body.sections,100).map(section=>{if(!section)invalid();const refs=references(section.citation_ids);if(refs.some(key=>!citationIds.includes(key)))invalid();return {heading:text(section.heading,512),text:text(section.text,200000,true),citationIds:refs};});
        return freeze({...summary,bodyTitle:text(body.title,512),entityId:id(body.entity_id),sections,citations,citationIds});
}
