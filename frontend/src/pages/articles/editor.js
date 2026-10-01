const editable = ['status','note','isRead','isFavorite','isIgnored'];
const draftOf = record => Object.fromEntries(editable.map(key => [key,record[key]]));
const initial = id => ({id,kind:'article',packageId:null,revision:0,status:'not_started',selfAssessment:'unassessed',note:'',artifactUrl:null,isRead:false,isFavorite:false,isIgnored:false});
export function createArticleEditor({ article, api, records, scope, onChange = () => {}, makeKey = () => globalThis.crypto.randomUUID() }) {
  let base=article.record ?? initial(article.id), draft=draftOf(base), remote=null, attempt=null, phase='editing', message='', pending=null, disposed=false;
  const dirty=()=>!disposed && (JSON.stringify(draft)!==JSON.stringify(draftOf(base)) || ['saving','checking','uncertain','conflict','comparison'].includes(phase));
  const snapshot=()=>disposed?null:Object.freeze({draft:Object.freeze({...draft}),revision:base.revision,remote,phase,message,busy:['saving','checking'].includes(phase)});
  const notify=()=>{if(!disposed&&scope.active)onChange(snapshot());};
  function dispose(){if(disposed)return;disposed=true;base=null;draft=null;remote=null;attempt=null;pending=null;message='';}
  scope.own(dispose);
  function command(){return {expected_revision:base.revision,status:draft.status,self_assessment:base.selfAssessment,note:draft.note,artifact_url:base.artifactUrl,is_read:draft.isRead,is_favorite:draft.isFavorite,is_ignored:draft.isIgnored};}
  return Object.freeze({
    get snapshot(){return snapshot();},get dirty(){return dirty();},dispose,
    change(key,value){
      if(disposed||!scope.active||['saving','checking','uncertain'].includes(phase))return;
      if(!editable.includes(key)||key==='note'&&typeof value!=='string'||key==='status'&&!['not_started','planned','learning','completed'].includes(value)||key.startsWith('is')&&typeof value!=='boolean')throw new TypeError('Invalid article edit');
      draft[key]=value;if(!['conflict','comparison'].includes(phase)){phase='editing';message='更改尚未保存。';}
    },
    save(){
      if(disposed||!scope.active)return Promise.resolve();if(pending)return pending;
      if(['conflict','comparison'].includes(phase))return Promise.resolve();
      if([...draft.note].length>10000){phase='error';message='笔记最多10,000个字符，请缩短后保存。';notify();return Promise.resolve();}
      if(!attempt)attempt={fields:Object.freeze(command()),key:makeKey()};
      const current=attempt;phase='saving';message='正在保存…';notify();
      pending=scope.run('article-record',signal=>records.save({kind:'article',id:article.id,packageId:null},current.fields,{signal,idempotencyKey:current.key}),value=>{
        if(disposed)return;base=value;draft=draftOf(value);attempt=null;remote=null;phase='saved';message='保存已确认。';notify();
      },error=>{
        if(disposed)return;
        if(error.status===409){phase='conflict';message='记录已变化或当前操作无法完成。输入已保留，请重新加载服务记录并对比。';attempt=null;}
        else if([403,404,410,422].includes(error.status)){phase='error';message=error.status===403?'无权保存此记录。':error.status===404||error.status===410?'此资讯已不可用，输入已保留。':'内容未通过校验，输入已保留。';attempt=null;}
        else{phase='uncertain';message='保存尚未确认。重试会提交刚才的内容，输入已保留。';}
        notify();
      }).finally(()=>{pending=null;});return pending;
    },
    reload(){
      if(disposed||!scope.active)return Promise.resolve();if(pending)return pending;
      phase='checking';message='正在核对服务记录，保留当前输入…';notify();
      pending=scope.run('article-reload',signal=>api.detail(article.id,{signal}),value=>{
        if(disposed)return;remote=value.record??initial(article.id);phase='comparison';message='已加载服务记录。请对比后决定是否沿用当前输入。';notify();
      },()=>{if(disposed)return;phase='conflict';message='服务记录暂时无法加载，当前输入已保留。';notify();}).finally(()=>{pending=null;});return pending;
    },
    acceptRemote(){if(disposed||phase!=='comparison')return;base=remote;remote=null;attempt=null;phase='editing';message='已采用服务端版本，当前输入仍待保存。';notify();}
  });
}
