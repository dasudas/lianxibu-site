import test from 'node:test';
import assert from 'node:assert/strict';
import { createLeaveDialog } from '../src/components/common/leave-dialog.js';
class Element extends EventTarget{constructor(tag,doc){super();this.tag=tag;this.ownerDocument=doc;this.children=[];this.attrs={};this.open=false;this.isConnected=true;}setAttribute(k,v){this.attrs[k]=v;}append(...v){this.children.push(...v);}showModal(){this.open=true;}close(){this.open=false;}remove(){this.isConnected=false;}focus(){this.ownerDocument.activeElement=this;}}
test('leave dialog cancels without action, confirms original request once and clears stale listeners on identity change',()=>{const doc={createElement:tag=>new Element(tag,doc),activeElement:null};const host=new Element('div',doc),view=createLeaveDialog(host);let actions=0;
 view.request(()=>actions++);const first=host.children.at(-1);assert.equal(first.open,true);const cancel=first.children[2],accept=first.children[3];view.request(()=>actions+=100);assert.equal(host.children.length,1);cancel.dispatchEvent(new Event('click'));assert.equal(actions,0);assert.equal(first.open,false);accept.dispatchEvent(new Event('click'));assert.equal(actions,0);
 view.request(()=>actions++);const next=host.children.at(-1);next.children[3].dispatchEvent(new Event('click'));next.children[3].dispatchEvent(new Event('click'));assert.equal(actions,1);
 view.request(()=>actions++);const stale=host.children.at(-1);view.clear();stale.children[3].dispatchEvent(new Event('click'));assert.equal(actions,1);view.dispose();view.request(()=>actions++);assert.equal(actions,1);
});
