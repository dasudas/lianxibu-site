import test from 'node:test';
import assert from 'node:assert/strict';
import { createReportsApi } from '../src/api/reports.js';
const summary = () => ({ id: 'fictional-report', kind: 'report', title: 'Fictional', revision: 2, body_revision: 1, publication_state: 'published', published_at: null, input_snapshot_id: null, generation: null, input_cutoff_at: null, coverage: { state: 'partial', missing_inputs: [], missing_dates: [], no_update_dates: [] }, review_required: false, blocking_reasons: [], affected_by_correction: false, supersedes_job_id: null, private: 'fictional omitted' });
const page = () => ({ page: 2, page_size: 20, total: 21, items: [summary()] });
test('report lists use explicit type and exact page metadata, safe complete links and no private extras', async () => {
  for (const [type, prefix] of [['daily','digests'],['weekly','weekly-reports'],['monthly','monthly-reports']]) {
    const signal = new AbortController().signal; let args;
    const api = createReportsApi({ request: async (...v) => { args = v; return { data: page() }; } });
    const result = await api.list({ type, page: 2, signal });
    assert.deepEqual(args, ['GET','/reports',{ signal, query: { type, page: 2, page_size: 20 } }]);
    assert.equal(result.items[0].href, `/${prefix}/fictional-report`); assert.equal(result.items[0].private, undefined); assert.ok(Object.isFrozen(result.items[0])); assert.equal(result.total,21);
  }
});
test('report list refuses invalid requests and service pages instead of inventing data', async () => {
  let calls=0; const api = createReportsApi({ request: async () => { calls++; return { data: page() }; } });
  for (const request of [{type:'unknown'}, {type:'weekly',page:0}, {type:'weekly',page_size:51}, {type:'daily',page:1.5}]) await assert.rejects(api.list(request));
  assert.equal(calls,0);
  const changes = [p=>p.page=1,p=>p.page_size=50,p=>p.total=0,p=>p.items.push(p.items[0]),p=>p.items[0].publication_state='draft',p=>p.items[0].review_required=true,p=>p.items[0].kind='model',p=>p.total=NaN];
  for (const change of changes) { const p=page();change(p); await assert.rejects(createReportsApi({request:async()=>({data:p})}).list({type:'weekly',page:2}), {code:'invalid_response'}); }
  const empty=await createReportsApi({request:async()=>({data:{items:[],page:9,page_size:20,total:1}})}).list({type:'weekly',page:9});assert.deepEqual(empty.items,[]);
  const failure={status:410}; await assert.rejects(createReportsApi({request:async()=>{throw failure;}}).list({type:'weekly'}),e=>e===failure);
});
