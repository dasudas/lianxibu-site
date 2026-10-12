import { ApiError, StaleSessionError } from './client.js';
const FEATURE = 'formal-business-v1';
export function validateCapabilities(value) {
  const fields=['protocol_revision','contract_version','supported_features','max_page_size','max_observations'];
  if (!value || typeof value!=='object' || Array.isArray(value)
      || Object.keys(value).length!==fields.length || fields.some(key=>!Object.hasOwn(value,key))
      || value.protocol_revision!=='0.12' || value.contract_version!=='0.15'
      || value.max_page_size!==50 || value.max_observations!==10
      || !Array.isArray(value.supported_features) || value.supported_features.length>64
      || value.supported_features.some(feature=>typeof feature!=='string'||[...feature].length<1||[...feature].length>64)
      || !value.supported_features.includes(FEATURE)) {
    throw new ApiError(0,'upgrade_required','网站服务尚未完成升级，请稍后重新检查。');
  }
  return true;
}
/** One check per application lifetime, before any business request. A restored page
 * creates a fresh instance. This metadata is never authentication or dispatch permission. */
export function createCapabilityClient(transport,{onStatus=()=>{}}={}) {
  let pending=null,disposed=false;const controller=new AbortController();
  function active(){if(disposed)throw new ApiError(0,'application_disposed','页面已关闭。');}
  function ensure(){
    active();
    if(!pending){
      onStatus('loading');
      pending=transport.request('GET','/capabilities',{signal:controller.signal}).then(response=>{
        active();validateCapabilities(response?.data);onStatus('ready');
      }).catch(error=>{
        if(disposed)throw new ApiError(0,'application_disposed','页面已关闭。');
        onStatus(error.code==='upgrade_required'?'unsupported':'unavailable');throw error;
      });
    }
    return pending;
  }
  async function guarded(action){active();const generation=transport.generation;await ensure();active();if(generation!==transport.generation)throw new StaleSessionError();return action();}
  return Object.freeze({
    request:(...args)=>guarded(()=>transport.request(...args)),
    list:(...args)=>guarded(()=>transport.list(...args)),
    getSession:(...args)=>guarded(()=>transport.getSession(...args)),
    // Session cleanup remains available when an older service cannot support business writes.
    endSession:(...args)=>transport.endSession(...args),
    clearSession:(...args)=>transport.clearSession(...args),
    subscribeSessionChange:(...args)=>transport.subscribeSessionChange(...args),
    get generation(){return transport.generation;},
    dispose(){if(disposed)return;disposed=true;controller.abort();}
  });
}
