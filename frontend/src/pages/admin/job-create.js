import {escape} from '../learning/shared.js';
import {adHocPrompt} from '../../api/job-actions.js';

export const taskCreationNames=Object.freeze({daily_report:'日报',weekly_report:'周报',monthly_report:'月报',news_dedup:'资讯去重',news_value:'资讯价值分析',agent_dedup:'Agent去重',skill_dedup:'Skill去重',workflow_dedup:'开发方案去重',model_dedup:'模型去重',agent_analysis:'Agent解读',skill_analysis:'Skill解读',workflow_analysis:'开发方案解读',model_analysis:'模型解读',ad_hoc_ai:'临时AI问题'});
const blank=()=>({taskType:'',targetKind:'',targetId:'',packageId:'',expectedRevision:'',reason:'',prompt:'',confirmed:false});
const id=v=>typeof v==='string'&&v.trim().length>0&&v.isWellFormed()&&[...v].length<=64&&!/[\u0000-\u001f\u007f-\u009f]/.test(v);
const writable=p=>['editing','rejected'].includes(p);
export function createJobCreator({scope,api,blocked=()=>false,makeKey=()=>crypto.randomUUID(),makeTarget=()=>crypto.randomUUID(),onChange=()=>{},onForbidden=()=>{}}){
  let disposed=false,pending=false,attempt=null,state={phase:'editing',fields:blank(),created:null,message:'',error:''};
  const active=()=>!disposed&&scope.active,emit=()=>{if(active())onChange(state);};
  function validate(){
    const f=state.fields;
    if(f.taskType==='ad_hoc_ai'){try{adHocPrompt(f.prompt);}catch{return '请填写完整原始问题，最多32768个Unicode字符，不能仅含空白。';}}
    else if(!Object.hasOwn(taskCreationNames,f.taskType)||!f.targetKind.trim()||!f.targetKind.isWellFormed()||[...f.targetKind].length>32||/[\u0000-\u001f\u007f-\u009f]/.test(f.targetKind)||!id(f.targetId)||f.packageId!==''&&!id(f.packageId))return '请明确任务类型、完整目标类别与编号，以及需要时的学习包编号。';
    if(f.taskType!=='ad_hoc_ai'&&(!/^(0|[1-9][0-9]*)$/.test(f.expectedRevision)||!Number.isSafeInteger(Number(f.expectedRevision))))return '请输入已经核对的目标版本；仅在明确不存在时填写0。';
    if(!f.reason.trim()||!f.reason.isWellFormed()||[...f.reason].length>2048||f.confirmed!==true)return '请填写原因并明确确认目标、版本和任务类型。';
    return '';
  }
  async function submit(){
    if(!active()||pending||blocked()||!writable(state.phase)&&state.phase!=='uncertain')return;
    if(!attempt){
      state.error=validate();if(state.error){emit();return;}
      const f=state.fields;
      const target=f.taskType==='ad_hoc_ai'?makeTarget():f.targetId;if(!id(target)){state.error='请求编号暂不可用，请稍后重新登记。';emit();return;}
      attempt={fields:{task_type:f.taskType,target:{kind:f.taskType==='ad_hoc_ai'?'ad_hoc_request':f.targetKind,id:target,package_id:f.taskType==='ad_hoc_ai'?null:f.packageId||null},expected_revision:f.taskType==='ad_hoc_ai'?0:Number(f.expectedRevision),reason:f.reason,...f.taskType==='ad_hoc_ai'?{request:{prompt:f.prompt}}:{}},idempotencyKey:makeKey()};
    }
    const frozen=structuredClone(attempt);pending=true;state.phase='sending';state.error='';state.message='正在受理任务…';emit();
    try{await scope.run('job-create',signal=>api.create({...frozen,signal}),job=>{
      state.created=job;state.fields=blank();attempt=null;state.phase='created';state.message='任务受理已确认；请查看该任务的输入和执行状态。';emit();
    },error=>{
      if([401,403].includes(error?.status)){attempt=null;state={phase:'forbidden',fields:null,created:null,error:'',message:'访问权限已失效，创建资料已清空。'};onForbidden();}
      else if(error?.status===410||error?.status===409&&error?.code?.includes('expired')){state.phase='expired';state.message='原对象或回执已过期，保留原资料；请核对原任务，不能换编号重发。';}
      else if(error?.status===409&&error?.code==='input_unchanged'&&state.fields.taskType==='ad_hoc_ai'){attempt=null;state.phase='duplicate';state.fields.confirmed=false;state.message='同一原始问题已有任务，请在任务目录核对。更换编号不会增加调用额度；可以清除草稿后登记不同问题。';}
      else if(error?.status===409){state.phase='conflict';state.fields.confirmed=false;state.message=state.fields.taskType==='ad_hoc_ai'?'原创建请求存在身份冲突，请核对原任务；清除草稿后可以登记不同问题。':'目标版本或条件已变化；核对目标当前状态后，明确采用核对后的版本。';}
      else if([400,404,422,429].includes(error?.status)){attempt=null;state.phase='rejected';state.fields.confirmed=false;state.message='创建未被接受；请核对目标、版本、资料或稍后再试。';}
      else{state.phase='uncertain';state.message='受理结果尚未确认，只能按原编号与原资料重试。';}
      emit();
    });}finally{pending=false;}
  }
  function dispose(){disposed=true;attempt=null;state=null;}scope.own(dispose);
  return Object.freeze({get snapshot(){return state&&structuredClone({...state,locked:blocked()});},get dirty(){return !!state&&(!!attempt||!!state.fields&&Object.entries(state.fields).some(([k,v])=>k!=='confirmed'&&v!==''));},submit,dispose,
    change(k,v){if(!active()||blocked()||!writable(state.phase)||!state.fields||k==='confirmed'||!Object.hasOwn(state.fields,k))return;state.fields[k]=String(v);state.fields.confirmed=false;state.error='';state.phase='editing';if(k==='taskType')emit();},
    confirm(v){if(active()&&!blocked()&&writable(state.phase))state.fields.confirmed=v===true;},
    adopt(){if(!active()||blocked()||state.phase!=='conflict'||state.fields?.taskType==='ad_hoc_ai')return;attempt=null;state.fields.confirmed=false;state.phase='editing';state.message='原资料保留；请输入独立核对后的版本，并重新确认。';emit();},
    reset(){if(!active()||blocked()||!['editing','rejected','created','duplicate'].includes(state.phase)&&!(state.phase==='conflict'&&state.fields?.taskType==='ad_hoc_ai'))return;attempt=null;state={phase:'editing',fields:blank(),created:null,message:'',error:''};emit();}
  });
}
export function jobCreatorMarkup(s,href){
  if(!s?.fields)return '';
  if(s.phase==='created')return `<h2>任务已受理</h2><p role="status">${escape(s.message)}</p><p>任务编号 ${escape(s.created.id)}</p><a class="button primary" data-route href="${escape(href(s.created.id))}">查看已受理任务</a><button class="button" type="button" data-job-create-action="reset">登记另一项任务</button>`;
  const f=s.fields,temporary=f.taskType==='ad_hoc_ai',disabled=s.locked||!writable(s.phase)?' disabled':'';
  const input=(name,label,extra='')=>`<label class="field">${label}<input name="${name}" value="${escape(f[name])}"${disabled}${extra}></label>`;
  const target=temporary?`<div class="field"><label for="job-create-prompt">原始问题</label><textarea id="job-create-prompt" name="prompt" rows="8" maxlength="65536" aria-describedby="job-create-prompt-help"${disabled}>${escape(f.prompt)}</textarea><span id="job-create-prompt-help">完整问题将永久保存，最多32768个Unicode字符。相同问题最多调用3次，更换任务编号不会增加额度。</span></div>`:`${input('targetKind','目标类别')}${input('targetId','目标完整编号')}${input('packageId','学习包编号（适用时填写）')}${input('expectedRevision','已核对的目标版本',' inputmode="numeric"')}`;
  const resetDuplicate=s.phase==='duplicate'||s.phase==='conflict'&&temporary;
  return `<h2>新建任务</h2><p>${temporary?'先保存完整原始问题，再进入任务队列。AI执行窗口为北京时间01:00–07:00，当前操作只登记问题。':'请从原对象核对完整目标和管理版本。此处只登记任务，是否可以执行由服务端判断；不会直接调用AI。'}</p>${s.created?`<p>已受理任务 ${escape(s.created.id)}</p><a class="button primary" data-route href="${escape(href(s.created.id))}">查看已受理任务</a>`:''}<form data-job-create-form novalidate aria-busy="${s.phase==='sending'}"><label class="field">任务类型<select name="taskType"${disabled}><option value="">请选择</option>${Object.entries(taskCreationNames).map(([k,v])=>`<option value="${k}"${f.taskType===k?' selected':''}>${v}</option>`).join('')}</select></label>${target}<div class="field"><label for="job-create-reason">${temporary?'登记原因（审计记录）':'创建原因'}</label><textarea id="job-create-reason" name="reason" rows="3"${temporary?' aria-describedby="job-create-reason-help"':''}${disabled}>${escape(f.reason)}</textarea>${temporary?'<span id="job-create-reason-help">说明为什么登记此问题；发送给AI的内容以“原始问题”为准。</span>':''}</div><label><input type="checkbox" name="confirmed"${f.confirmed?' checked':''}${disabled}>${temporary?'我已核对完整原始问题和登记原因，确认保存并排队':'我已核对任务类型、完整目标、版本和原因，确认登记'}</label>${s.error?`<p role="alert" tabindex="-1" data-job-create-error>${escape(s.error)}</p>`:''}<p role="status" data-job-create-feedback>${escape(s.message)}</p><div class="form-actions">${writable(s.phase)?`<button class="button primary" type="submit"${disabled}>${temporary?'确认保存问题并排队':'确认登记任务'}</button><button class="button" type="button" data-job-create-action="reset"${disabled}>清除创建草稿</button>`:''}${s.phase==='uncertain'?'<button class="button" type="button" data-job-create-action="submit">重试原创建请求</button>':''}${s.phase==='conflict'&&!temporary?'<button class="button" type="button" data-job-create-action="adopt">目标已独立核对，修改版本并重新确认</button>':''}${resetDuplicate?'<button class="button" type="button" data-job-create-action="reset">清除草稿，登记不同问题</button>':''}${s.phase==='created'?'<button class="button" type="button" data-job-create-action="reset">登记另一项任务</button>':''}</div></form>`;
}
