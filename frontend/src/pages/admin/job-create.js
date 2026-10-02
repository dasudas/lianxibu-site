import {escape} from '../learning/shared.js';

export const taskCreationNames=Object.freeze({daily_report:'日报',weekly_report:'周报',monthly_report:'月报',news_dedup:'资讯去重',news_value:'资讯价值分析',agent_dedup:'Agent去重',skill_dedup:'Skill去重',workflow_dedup:'开发方案去重',model_dedup:'模型去重',agent_analysis:'Agent解读',skill_analysis:'Skill解读',workflow_analysis:'开发方案解读',model_analysis:'模型解读',ad_hoc:'临时AI任务'});
const blank=()=>({taskType:'',targetKind:'',targetId:'',packageId:'',expectedRevision:'',reason:'',confirmed:false});
const id=v=>typeof v==='string'&&v.trim().length>0&&v.isWellFormed()&&[...v].length<=64&&!/[\u0000-\u001f\u007f-\u009f]/.test(v);
const writable=p=>['editing','rejected'].includes(p);
export function createJobCreator({scope,api,blocked=()=>false,makeKey=()=>crypto.randomUUID(),onChange=()=>{},onForbidden=()=>{}}){
  let disposed=false,pending=false,attempt=null,state={phase:'editing',fields:blank(),created:null,message:'',error:''};
  const active=()=>!disposed&&scope.active,emit=()=>{if(active())onChange(state);};
  function validate(){
    const f=state.fields;
    if(!Object.hasOwn(taskCreationNames,f.taskType)||!f.targetKind.trim()||!f.targetKind.isWellFormed()||[...f.targetKind].length>32||/[\u0000-\u001f\u007f-\u009f]/.test(f.targetKind)||!id(f.targetId)||f.packageId!==''&&!id(f.packageId))return '请明确任务类型、完整目标类别与编号，以及需要时的学习包编号。';
    if(!/^(0|[1-9][0-9]*)$/.test(f.expectedRevision)||!Number.isSafeInteger(Number(f.expectedRevision)))return '请输入已经核对的目标版本；仅在明确不存在时填写0。';
    if(!f.reason.trim()||!f.reason.isWellFormed()||[...f.reason].length>2048||f.confirmed!==true)return '请填写原因并明确确认目标、版本和任务类型。';
    return '';
  }
  async function submit(){
    if(!active()||pending||blocked()||!writable(state.phase)&&state.phase!=='uncertain')return;
    if(!attempt){
      state.error=validate();if(state.error){emit();return;}
      const f=state.fields;
      attempt={fields:{task_type:f.taskType,target:{kind:f.targetKind,id:f.targetId,package_id:f.packageId||null},expected_revision:Number(f.expectedRevision),reason:f.reason},idempotencyKey:makeKey()};
    }
    const frozen=structuredClone(attempt);pending=true;state.phase='sending';state.error='';state.message='正在受理任务…';emit();
    try{await scope.run('job-create',signal=>api.create({...frozen,signal}),job=>{
      state.created=job;state.fields=blank();attempt=null;state.phase='created';state.message='任务受理已确认；请查看该任务的输入和执行状态。';emit();
    },error=>{
      if([401,403].includes(error?.status)){attempt=null;state={phase:'forbidden',fields:null,created:null,error:'',message:'访问权限已失效，创建资料已清空。'};onForbidden();}
      else if(error?.status===410||error?.status===409&&error?.code?.includes('expired')){state.phase='expired';state.message='原对象或回执已过期，保留原资料；请核对原任务，不能换编号重发。';}
      else if(error?.status===409){state.phase='conflict';state.fields.confirmed=false;state.message='目标版本或条件已变化；核对目标当前状态后，明确采用核对后的版本。';}
      else if([400,404,422,429].includes(error?.status)){attempt=null;state.phase='rejected';state.fields.confirmed=false;state.message='创建未被接受；请核对目标、版本、资料或稍后再试。';}
      else{state.phase='uncertain';state.message='受理结果尚未确认，只能按原编号与原资料重试。';}
      emit();
    });}finally{pending=false;}
  }
  function dispose(){disposed=true;attempt=null;state=null;}scope.own(dispose);
  return Object.freeze({get snapshot(){return state&&structuredClone({...state,locked:blocked()});},get dirty(){return !!state&&(!!attempt||!!state.fields&&Object.entries(state.fields).some(([k,v])=>k!=='confirmed'&&v!==''));},submit,dispose,
    change(k,v){if(!active()||blocked()||!writable(state.phase)||!state.fields||k==='confirmed'||!Object.hasOwn(state.fields,k))return;state.fields[k]=String(v);state.fields.confirmed=false;state.error='';state.phase='editing';},
    confirm(v){if(active()&&!blocked()&&writable(state.phase))state.fields.confirmed=v===true;},
    adopt(){if(!active()||blocked()||state.phase!=='conflict')return;attempt=null;state.fields.confirmed=false;state.phase='editing';state.message='原资料保留；请输入独立核对后的版本，并重新确认。';emit();},
    reset(){if(!active()||blocked()||!['editing','rejected','created'].includes(state.phase))return;attempt=null;state={phase:'editing',fields:blank(),created:null,message:'',error:''};emit();}
  });
}
export function jobCreatorMarkup(s,href){
  if(!s?.fields)return '';
  const f=s.fields,disabled=s.locked||!writable(s.phase)?' disabled':'';
  const input=(name,label,extra='')=>`<label class="field">${label}<input name="${name}" value="${escape(f[name])}"${disabled}${extra}></label>`;
  return `<h2>新建任务</h2><p>请从原对象核对完整目标和管理版本。此处只登记任务，是否可以执行由服务端判断；不会直接调用AI。</p>${s.created?`<p>已受理任务 ${escape(s.created.id)}</p><a class="button primary" data-route href="${escape(href(s.created.id))}">查看已受理任务</a>`:''}<form data-job-create-form novalidate><label class="field">任务类型<select name="taskType"${disabled}><option value="">请选择</option>${Object.entries(taskCreationNames).map(([k,v])=>`<option value="${k}"${f.taskType===k?' selected':''}>${v}</option>`).join('')}</select></label>${input('targetKind','目标类别')}${input('targetId','目标完整编号')}${input('packageId','学习包编号（适用时填写）')}${input('expectedRevision','已核对的目标版本',' inputmode="numeric"')}<label class="field">创建原因<textarea name="reason" rows="3"${disabled}>${escape(f.reason)}</textarea></label><label><input type="checkbox" name="confirmed"${f.confirmed?' checked':''}${disabled}>我已核对任务类型、完整目标、版本和原因，确认登记</label>${s.error?`<p role="alert" tabindex="-1" data-job-create-error>${escape(s.error)}</p>`:''}<p role="status" data-job-create-feedback>${escape(s.message)}</p><div class="form-actions">${writable(s.phase)?`<button class="button primary" type="submit"${disabled}>确认登记任务</button><button class="button" type="button" data-job-create-action="reset"${disabled}>清除创建草稿</button>`:''}${s.phase==='uncertain'?'<button class="button" type="button" data-job-create-action="submit">重试原创建请求</button>':''}${s.phase==='conflict'?'<button class="button" type="button" data-job-create-action="adopt">目标已独立核对，修改版本并重新确认</button>':''}${s.phase==='created'?'<button class="button" type="button" data-job-create-action="reset">登记另一项任务</button>':''}</div></form>`;
}
