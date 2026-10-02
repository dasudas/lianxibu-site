import test from 'node:test';
import assert from 'node:assert/strict';
import {createLearningApi} from '../src/api/learning.js';
import {catalogQuery, createLearningCatalog, catalogMarkup} from '../src/pages/learning/catalog.js';
import {createApiClient} from '../src/api/client.js';
import {createSessionState} from '../src/state/session.js';
import {createPageScope} from '../src/state/page-scope.js';
import {createLearningRecordEditor} from '../src/components/learning/record-editor.js';

const packageId='fictional-package', questionId='fictional-question';
const question=(state, answer)=>({package_id:packageId,id:questionId,title:'Fictional question',
  question:'Fictional prompt',difficulty:'beginner',kind:'concept',assessment_points:[],
  answer_state:state,generation_state:'reviewed',revision:2,...answer===undefined?{}:{answer}});

test('published answer is removed after withdrawal and hidden answer payloads cannot restore it', async()=>{
  let wire=question('published','Fictional former public answer');
  const api=createLearningApi({request:async(_method,path)=>({data:path.endsWith('/'+questionId)?wire:
    {id:packageId,title:'Fictional package',description:'',revision:1,state:'published'}})});
  const scope=createPageScope({snapshot:{generation:0},subscribe:()=>()=>{}});
  const query=catalogQuery(new URL('https://example.invalid/questions/'+questionId+'?package_id='+packageId),'questions');
  const navigations=[];
  const model=createLearningCatalog({scope,api,homeApi:{load:async()=>{throw Error('Unexpected default lookup');}},query,navigate:(...v)=>navigations.push(v)});
  try {
    await model.load(); assert.match(catalogMarkup(model.snapshot,query),/Fictional former public answer/);
    wire=question('withdrawn'); await model.load();
    assert.equal(model.snapshot.phase,'ready');
    assert.match(catalogMarkup(model.snapshot,query),/参考答案已撤回/);
    assert.doesNotMatch(catalogMarkup(model.snapshot,query),/Fictional former public answer|data-reference-answer/);
    for(const state of ['draft','approved','withdrawn']) {
      wire=question(state,'Fictional forbidden answer'); await model.load();
      assert.equal(model.snapshot.phase,'error');
      assert.doesNotMatch(catalogMarkup(model.snapshot,query),/Fictional forbidden answer|data-reference-answer/);
    }
    assert.deepEqual(navigations,[]);
  } finally {scope.dispose();}
});

test('switching users clears private memory and an old response cannot enter the new user editor', async()=>{
  let userId='fictional-user-a', finishOld;
  const api=createApiClient({fetchImpl:async()=>({status:200,ok:true,json:async()=>({data:{
    authenticated:true,user_id:userId,role:'user',csrf_token:'fictional-csrf'}})})});
  const session=createSessionState(api); await session.refresh();
  const identity={packageId,kind:'question',id:questionId};
  const record=note=>({...identity,revision:1,status:'planned',selfAssessment:'partial',note,
    artifactUrl:null,latestAnswerText:null,latestSubmissionUrl:null,confirmedBodyRevision:1,
    updatedAt:'2026-01-01T00:00:00.000000Z'});
  const oldScope=createPageScope(session);
  const oldEditor=createLearningRecordEditor({scope:oldScope,identity,bodyRevision:2,
    api:{read:()=>new Promise(resolve=>{finishOld=resolve;})}});
  session.remember('fictional-note','Fictional user A memory');
  const pending=oldEditor.read();
  session.clear(); assert.equal(oldScope.active,false); assert.equal(oldEditor.snapshot,null);
  assert.equal(session.recall('fictional-note'),undefined);
  userId='fictional-user-b'; await session.refresh();
  assert.equal(session.snapshot.user.id,userId);
  const newScope=createPageScope(session);
  const newEditor=createLearningRecordEditor({scope:newScope,identity,bodyRevision:2,
    api:{read:async()=>record('Fictional user B note')}});
  try {
    await newEditor.read(); finishOld(record('Fictional user A late note')); await pending;
    assert.equal(oldEditor.snapshot,null);
    assert.doesNotMatch(JSON.stringify(newEditor.snapshot),/user A/);
    assert.match(JSON.stringify(newEditor.snapshot),/Fictional user B note/);
    assert.equal(session.recall('fictional-note'),undefined);
  } finally {newScope.dispose();oldScope.dispose();session.dispose();}
});
