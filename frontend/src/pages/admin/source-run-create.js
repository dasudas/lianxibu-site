import {escape} from '../learning/shared.js';
const blank=()=>({businessDate:'',windowStart:'',windowEnd:'',runKind:'historical_backfill',reason:'',confirmed:false});
const editable=p=>['editing','rejected'].includes(p);
function validTime(v){const d=new Date(v);return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(v)&&v.length===27&&Number.isFinite(+d)&&d.toISOString().slice(0,23)===v.slice(0,23);}
function validate(f){
 const day=new Date(f.businessDate+'T00:00:00Z');
 if(!/^\d{4}-\d{2}-\d{2}$/.test(f.businessDate)||f.businessDate.length!==10||!Number.isFinite(+day)||day.toISOString().slice(0,10)!==f.businessDate)return '请填写有效的北京时间业务日期。';
 const start=+day-8*3600000,end=start+86400000,wire=n=>new Date(n).toISOString().replace('.000Z','.000000Z');
 if(!validTime(f.windowStart)||!validTime(f.windowEnd)||f.windowStart>=f.windowEnd||f.windowStart<wire(start)||f.windowEnd>wire(end))return '窗口须在该北京时间业务日期内，结束不包含；UTC时间须保留六位小数。';
 if(!['historical_backfill','production_qualification'].includes(f.runKind)||!f.reason.trim()||!f.reason.isWellFormed()||[...f.reason].length>2048||!f.confirmed)return '请填写原因，并确认正式来源、配置版本及完整窗口。';
 return '';
}
export function createSourceRunCreator({scope,api,sourceApi,sourceId,makeKey=()=>crypto.randomUUID(),onChange=()=>{},onForbidden=()=>{}}){
 let disposed=false,pending=false,attempt=null,state={phase:'editing',fields:blank(),source:null,remote:null,created:null,error:'',message:''};
 const active=()=>!disposed&&scope.active&&state.phase!=='forbidden',emit=()=>{if(!disposed&&scope.active)onChange();};
 const target=s=>s&&s.id===sourceId&&Number.isSafeInteger(s.revision)&&s.revision>=0&&Number.isSafeInteger(s.configRevision)&&s.configRevision>=0;
 function forbidden(){attempt=null;state={phase:'forbidden',fields:null,source:null,remote:null,created:null,error:'',message:''};onForbidden();emit();}
 async function submit(){
  if(!active()||pending||!editable(state.phase)&&state.phase!=='uncertain')return;
  if(!attempt){
   state.error=validate(state.fields);if(!target(state.source)||state.source.activationStage!=='production')state.error='请先读取当前正式来源；停用或试运行来源不能提交。';
   if(state.error){emit();return;}
   attempt={target:{id:sourceId,revision:state.source.revision,configRevision:state.source.configRevision,activationStage:state.source.activationStage},options:{businessDate:state.fields.businessDate,windowStart:state.fields.windowStart,windowEnd:state.fields.windowEnd,runKind:state.fields.runKind,reason:state.fields.reason,idempotencyKey:makeKey()}};
  }
  const frozen=structuredClone(attempt);pending=true;state.phase='sending';state.error='';state.message='正在登记正式运行…';emit();
  try{await scope.run('source-run-create',signal=>api.create(frozen.target,{...frozen.options,signal}),r=>{
   if(!active())return;state.created=r;attempt=null;state.fields=blank();state.phase='created';state.message='正式运行登记已确认；采集和分析状态请打开该运行查看。';emit();
  },e=>{
   if(!active())return;
   if([401,403].includes(e?.status)){forbidden();return;}
   if(e?.status===410||e?.status===409&&e?.code?.includes('expired')){state.phase='expired';state.message='原回执或来源已过期，保留原请求资料，请核对原运行。';}
   else if(e?.status===409){state.phase='conflict';state.fields.confirmed=false;state.message='来源或配置已变化；读取当前来源并明确采用后重新确认。';}
   else if([400,404,422,429].includes(e?.status)){attempt=null;state.phase='rejected';state.fields.confirmed=false;state.message='登记未被接受，请核对来源、窗口和原因。';}
   else{state.phase='uncertain';state.message='登记结果尚未确认，只能按原来源、窗口、版本与编号重试。';}emit();
  });}finally{pending=false;}
 }
 async function readCurrent(){
  if(!active()||pending||!['editing','rejected','conflict','conflict_read_error','conflict_current'].includes(state.phase))return;
  const conflict=state.phase.startsWith('conflict');pending=true;state.phase=conflict?'conflict_reading':'reading';emit();
  try{await scope.run('source-run-create-source',signal=>sourceApi.read(sourceId,{signal}),s=>{
   if(!active())return;if(!target(s)){state.phase=conflict?'conflict_read_error':'rejected';state.message='来源身份或版本无法核对，请重试读取。';emit();return;}
   if(conflict){state.remote=s;state.phase='conflict_current';state.message='当前来源已读取；采用后须重新确认，原窗口和原因保留。';}
   else{state.source=s;state.fields.confirmed=false;state.phase='editing';state.message='当前来源已读取，请核对版本与窗口。';}emit();
  },e=>{if(!active())return;if([401,403].includes(e?.status)){forbidden();return;}state.phase=conflict?'conflict_read_error':'rejected';state.message='当前来源暂时无法读取；原资料保留。';emit();});}finally{pending=false;}
 }
 function dispose(){disposed=true;attempt=null;state=null;}scope.own(dispose);
 return Object.freeze({get snapshot(){return state&&structuredClone(state);},get dirty(){return !!state&&(!!attempt||!!state.fields&&Object.entries(state.fields).some(([k,v])=>!['runKind','confirmed'].includes(k)&&v!==''));},submit,readCurrent,dispose,
  sync(s){if(active()&&editable(state.phase)&&!this.dirty&&target(s)){state.source=s;state.fields.confirmed=false;}},
  change(k,v){if(active()&&editable(state.phase)&&k!=='confirmed'&&Object.hasOwn(state.fields,k)){state.fields[k]=String(v);state.fields.confirmed=false;state.error='';}},
  confirm(v){if(active()&&editable(state.phase))state.fields.confirmed=v===true;},
  copy(r){if(!active()||!editable(state.phase)||!r||r.sourceId!==sourceId)return;state.fields={...state.fields,businessDate:r.businessDate,windowStart:r.windowStart,windowEnd:r.windowEnd,confirmed:false};state.message='已复制明确所选运行的原始窗口；将登记新的正式运行，旧运行保持原状。';emit();},
  adopt(){if(!active()||state.phase!=='conflict_current'||!target(state.remote))return;state.source=state.remote;state.remote=null;attempt=null;state.fields.confirmed=false;state.phase='editing';state.message='已采用读取的来源版本；窗口和原因保留，请重新确认。';emit();},
  reset(){if(!active()||!['editing','rejected','created'].includes(state.phase))return;attempt=null;state.fields=blank();state.created=null;state.error='';state.message='';state.phase='editing';emit();}
 });
}
export function sourceRunCreatorMarkup(s,href,selected){
 if(!s?.fields)return '';const f=s.fields,disabled=editable(s.phase)?'':' disabled',current=s.source;
 const input=(name,label,extra='')=>`<label class="field">${label}<input name="${name}" value="${escape(f[name])}"${extra}${disabled}></label>`;
 return `<section class="source-editor" aria-label="登记正式运行"><h2>登记正式运行</h2><p>仅正式来源可登记。采集窗口以UTC填写，保留原始六位小数；结束时间不包含在窗口内。</p><p>${current?`来源 ${escape(current.id)} · 管理版本 ${current.revision} · 配置版本 ${current.configRevision} · ${current.activationStage==='production'?'正式来源':'当前不是正式来源'}`:'请先读取当前来源。'}</p>${s.remote?`<p>当前读取版本 ${s.remote.revision} · 配置版本 ${s.remote.configRevision} · ${s.remote.activationStage==='production'?'正式来源':'当前不是正式来源'}</p>`:''}${s.created?`<p>已登记运行 ${escape(s.created.runId)}</p><a class="button primary" data-route href="${escape(href(s.created.runId))}">查看已登记运行</a>`:''}<form data-source-run-create-form novalidate><label class="field">正式运行类别<select name="runKind"${disabled}><option value="historical_backfill"${f.runKind==='historical_backfill'?' selected':''}>历史补采</option><option value="production_qualification"${f.runKind==='production_qualification'?' selected':''}>正式资格采集</option></select></label>${input('businessDate','北京时间业务日期',' type="date"')}${input('windowStart','UTC窗口开始（含）',' placeholder="2026-01-01T16:00:00.000000Z"')}${input('windowEnd','UTC窗口结束（不含）',' placeholder="2026-01-02T16:00:00.000000Z"')}<label class="field">登记原因<textarea name="reason" rows="3"${disabled}>${escape(f.reason)}</textarea></label><label><input type="checkbox" name="confirmed"${f.confirmed?' checked':''}${disabled}>我已核对正式来源、管理和配置版本、业务日期与完整窗口</label>${s.error?`<p role="alert" tabindex="-1" data-source-run-create-error>${escape(s.error)}</p>`:''}<p role="status">${escape(s.message)}</p><div class="form-actions">${editable(s.phase)?`<button class="button primary" type="submit">确认登记正式运行</button><button class="button" type="button" data-source-run-create-action="readCurrent">读取当前来源</button>${selected?'<button class="button" type="button" data-source-run-create-action="copy">采用所选运行窗口</button>':''}<button class="button" type="button" data-source-run-create-action="reset">清除登记草稿</button>`:''}${s.phase==='uncertain'?'<button class="button" type="button" data-source-run-create-action="submit">重试原登记请求</button>':''}${['conflict','conflict_read_error','conflict_current'].includes(s.phase)?'<button class="button" type="button" data-source-run-create-action="readCurrent">读取当前来源</button>':''}${s.phase==='conflict_current'?'<button class="button" type="button" data-source-run-create-action="adopt">采用已读取的来源版本</button>':''}${s.phase==='created'?'<button class="button" type="button" data-source-run-create-action="reset">登记另一项正式运行</button>':''}</div></form></section>`;
}
