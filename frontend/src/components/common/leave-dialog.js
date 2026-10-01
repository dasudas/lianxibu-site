export function createLeaveDialog(host) {
  let dialog=null,resume=null,removeListeners=null,previousFocus=null,disposed=false;
  function clear(){
    resume=null;removeListeners?.();removeListeners=null;
    if(dialog){if(dialog.open)dialog.close();dialog.remove();dialog=null;}
    if(previousFocus?.isConnected)previousFocus.focus();previousFocus=null;
  }
  return Object.freeze({clear,dispose(){if(disposed)return;disposed=true;clear();},request(action){
    if(disposed||dialog)return;if(typeof action!=='function')throw new TypeError('Leave action required');
    const doc=host.ownerDocument;previousFocus=doc.activeElement;resume=action;dialog=doc.createElement('dialog');dialog.setAttribute('aria-labelledby','leave-dialog-title');
    const title=doc.createElement('h2');title.id='leave-dialog-title';title.textContent='有更改尚未保存或确认';
    const text=doc.createElement('p');text.textContent='离开可能丢失当前输入。可以留在此页继续编辑或核对保存结果。';
    const cancel=doc.createElement('button');cancel.type='button';cancel.className='button';cancel.textContent='留在此页';
    const confirm=doc.createElement('button');confirm.type='button';confirm.className='button primary';confirm.textContent='继续离开';
    const stay=()=>clear(),leave=()=>{const action=resume;clear();action?.();},escape=event=>{event.preventDefault();clear();};
    cancel.addEventListener('click',stay);confirm.addEventListener('click',leave);dialog.addEventListener('cancel',escape);
    const current=dialog;removeListeners=()=>{cancel.removeEventListener('click',stay);confirm.removeEventListener('click',leave);current.removeEventListener('cancel',escape);};
    dialog.append(title,text,cancel,confirm);host.append(dialog);dialog.showModal();cancel.focus();
  }});
}
