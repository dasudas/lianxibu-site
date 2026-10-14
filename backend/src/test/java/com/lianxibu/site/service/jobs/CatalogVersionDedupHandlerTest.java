package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Native full INPUT/request/claim/send/retained/APPLIED chain; external response and downstream are fictional ports. */
class CatalogVersionDedupHandlerTest extends CatalogVersionJudgmentValueTest {
    static final String DEDUP_ROOT="fictional-dedup-root";
    CatalogVersionCommitPermitService.Captured captured;
    String snapshot,raw;Input original;boolean failCompletion;int completions;
    DedupResultHandler handler;
    void boot(String category,boolean recommend,boolean includeFrame){
        initialize(category,false);String text=mysql()?"JSON":"CLOB",time=mysql()?"DATETIME(6)":"TIMESTAMP";
        for(String column:List.of("repeat_count BIGINT DEFAULT 0","last_seen_at "+time,"last_occurrence_at "+time))f.jdbc.execute("ALTER TABLE analyzed_entities ADD "+column);
        for(String column:List.of("counted_occurrence_key BINARY(32) UNIQUE","is_new INT","dedup_json "+text))f.jdbc.execute("ALTER TABLE source_run_items ADD "+column);
        f.jdbc.update("UPDATE analyzed_entities SET first_seen_business_date='2026-01-01',occurrence_count=1,last_seen_at=?",java.sql.Timestamp.from(f.clock.instant().minusSeconds(2)));
        var p=projection();var context=(ObjectNode)CanonicalJson.parse(input(p).stage()==Stage.AGENT_DEDUP?FrozenApplicationContextTest.context(Stage.AGENT_DEDUP):FrozenApplicationContextTest.context(input(p).stage()));
        context.put("dedup_result_version","2");context.putArray("item_ids").add(p.memberId());context.putArray("candidates_by_item").addObject().put("item_id",p.memberId()).putArray("entity_ids").add(p.entityId());context.putArray("eligible_high_value_item_ids");context.putArray("allowed_citation_ids");context.putArray("reference_targets");context.putArray("version_eligible_item_ids").add(p.memberId());
        var q=p.resultConstraint();var row=context.putArray("catalog_version_qualifications").addObject().put("item_id",p.memberId()).put("entity_id",p.entityId()).put("version_changed",q.versionChanged());row.putObject("previous_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",q.previousProofHash());row.putObject("new_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",q.newProofHash());
        var ctx=FrozenApplicationContext.fromJson(context);var settings=new FrozenGenerationSettings(1,List.of(),List.of());var command=new Prepare("fictional-dedup-key",1,ctx.stage(),LocalDate.of(2026,1,1),null,List.of(),List.of(),List.of(),true,settings.semanticHash(),ctx);
        var node=JsonMapper.shared().createObjectNode().put("schema_version","1").put("logical_key",command.logicalKey()).put("generation",1).put("stage",f.category+"_dedup").put("business_date","2026-01-01").put("settings_hash",command.settingsHash()).put("cutoff",p.toJson().get("cutoff").asText()).put("coverage_state","complete");node.putArray("missing");node.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(List.of(),List.of()));node.set(FrozenApplicationContext.FIELD,ctx.toJson());
        var member=node.putObject(FrozenMemberBindings.FIELD).put("schema_version","frozen-member-bindings-v1").putArray("members").addObject();String identity=f.jdbc.queryForObject("SELECT identity_key FROM analyzed_entities",String.class);
        member.put("item_id",p.memberId()).put("expected_revision",3).put("category",category).put("identity_key",identity).put("title","Fictional catalog release").putNull("summary").put("new_entity_id",FrozenMemberBindings.entityId(DEDUP_ROOT,p.memberId(),category,identity));
        node.set(FrozenGenerationSettings.FIELD,settings.toJson());var evidence=node.putArray("members").addObject().put("id",p.memberId()).put("kind","source_member").put("business_date","2026-01-01").put("completed_at",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(f.clock.instant().minusSeconds(1))).put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,current));evidence.set("body",current.deepCopy());evidence.putArray("references");
        var temp=ctx.input(DEDUP_ROOT,"fictional-preparation-attempt","c".repeat(64),0);if(includeFrame)node.set(FrozenCatalogVersionProjections.FIELD,FrozenCatalogVersionProjections.create(List.of(p),temp).toJson());
        snapshot=JdbcFrozenInputs.canonical(node);String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,node);String request=new GenerationRequestBuilder().build(ctx.stage(),snapshot,hash);
        // Explicit trusted fixture preparation exercises the native persistence core, not source-wide handoff admission.
        var frozen=new Frozen(DEDUP_ROOT,command.logicalKey(),1,ctx.stage(),f.clock.instant(),"complete",List.of(),snapshot,hash,request,ctx.stage().priority(),f.clock.instant(),"queued");var storage=new GenerationJdbcStore(f.source,f.clock,"fictional-owner",DEDUP_ROOT,command);storage.primePureInput(frozen);
        f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-dedup-prepare",DEDUP_ROOT,hash),storage::committed,a->{storage.save(frozen,a.actionKey());return frozen;});
        var claim=f.worker.claim(new SharedWriteExecutor.ActionContext("fictional-dedup-claim",DEDUP_ROOT,hash));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());f.permit=claim.permit();f.identity=f.permit.identity();assertEquals(WorkerAuthorizationService.State.SENT,f.worker.dispatch(f.permit,r->{f.sends++;return "fictional v2 response";}).state());
        var reads=new ContentReadService(new JdbcContentReads(f.source),f.tx,ContentReadService.denyUnverifiedReferences());f.store=new ResultJdbcStore(f.source,f.identity,reads,t->{throw new AssertionError("no fixture references expected");},f.clock,"fictional-owner");original=f.store.input();raw=ResultApplicationService.providerEnvelope(original,payload(p,recommend).toString());
        var batches=new CatalogVersionBatchPreflightService(f.source,f.tx,category);var versions=new CatalogVersionBatchJudgmentWriter(f.source,batches.permits(),new EntityAnalysisStateService(new JdbcEntityAnalysisState(f.source)),category,f.clock);
        var entities=new EntityDedupService(new JdbcEntityDedup(f.source),f.writes,new EntityDecisionJdbcLog(new JdbcEntityDecisions(f.source),"fictional-owner",f.clock),f.clock);
        var downstream=new DedupResultHandler.Downstream(){
            public void afterApplied(Input i,Validated r,List<EntityDedupService.Decision> decisions,Set<String> reanalyze){throw new AssertionError("v2 requires first-judgment completion port");}
            public void afterCatalogApplied(Input i,Validated r,List<EntityDedupService.Decision> decisions,CatalogVersionBatchJudgmentWriter.Outcome outcome){
                completions++;assertEquals(1,decisions.size());assertEquals("fictional-entity",decisions.getFirst().entityId());var stored=outcome.judgments().get(p.memberId());assertNotNull(stored);var nativeStored=new CatalogVersionJudgmentLedger(new JdbcCatalogVersionJudgments(f.source),category).lockAndRead(stored.judgmentKey()).orElseThrow();assertEquals(stored.json(),nativeStored.json());if(failCompletion)throw new IllegalStateException("fictional downstream failure");
            }
        };
        handler=new DedupResultHandler(entities,new JdbcFrozenInputs(f.source)::read,downstream,batches,versions);install(handler);
    }
    void install(DedupResultHandler value){Handler unused=(a,b,c,d)->{throw new AssertionError("unassembled branch");};f.service=new ResultApplicationService(f.writes,f.store,Map.of("dedup",value,"analysis",unused,"report",unused,"value",unused,"ad_hoc",unused));}
    Result apply(){return f.service.applyAutomatic(original,raw,new SharedWriteExecutor.ActionContext("fictional-dedup-apply",DEDUP_ROOT,original.inputHash()));}
    @Test void FourCategoryFullNativeOriginalModelChainAppliesFirstJudgmentAndMemberOnce(){
        for(String cat:List.of("agent","skill","workflow","model")){
            boot(cat,true,true);assertEquals(Status.APPLIED,apply().status());assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals("fictional-entity",f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));
            assertEquals("APPLIED",new JdbcResultState(f.source).root(DEDUP_ROOT).application());assertEquals(1,new JdbcJobRecords(f.source).byId(DEDUP_ROOT).callCount());assertEquals(2,f.sends);int previous=completions;
            f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-current-item'");assertEquals(Status.APPLIED,apply().status());assertEquals(previous,completions);assertEquals(2,f.sends);
        }
    }
    @Test void DownstreamFailurePreservesOriginalResponseButRollsBackMemberLedgerAndGeneration(){
        boot("agent",true,true);failCompletion=true;assertThrows(RuntimeException.class,this::apply);assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertNull(f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type IN ('catalog_version_judgment','entity_decision')",Integer.class));assertNotNull(new JdbcResultState(f.source).root(DEDUP_ROOT).resultHash());assertEquals("occupied",new JdbcWorkerState(f.source).slot().state());failCompletion=false;assertEquals(Status.APPLIED,apply().status());assertEquals(2,f.sends);
    }
    @Test void MissingNativeFrameOrLegacyHandlerCannotApplyUnqualifiedV2Members(){
        boot("agent",true,false);assertThrows(RuntimeException.class,this::apply);assertNotNull(new JdbcResultState(f.source).root(DEDUP_ROOT).resultHash());assertNull(f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id='fictional-current-item'",String.class));
        boot("agent",true,true);var entities=new EntityDedupService(new JdbcEntityDedup(f.source),f.writes,new EntityDecisionJdbcLog(new JdbcEntityDecisions(f.source),"fictional-owner",f.clock),f.clock);install(new DedupResultHandler(entities,new JdbcFrozenInputs(f.source)::read,(i,r,d,ids)->{throw new AssertionError("must reject before downstream");}));assertThrows(RuntimeException.class,this::apply);assertNotNull(new JdbcResultState(f.source).root(DEDUP_ROOT).resultHash());assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));
    }
    @Test void NegativeOriginalJudgmentAndCommittedAckUnknownDoNotAllocateAnotherGeneration(){
        boot("agent",false,true);f.tx.ackLost=true;assertEquals(Status.APPLIED,apply().status());assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(Status.APPLIED,apply().status());assertEquals(2,f.sends);
    }
    Validated retainedValue(){return ResultApplicationService.validate(original,raw);}
    Applied applyPrepared(Handler prepared,Validated value,boolean manual){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-confirmed-handler",DEDUP_ROOT,original.inputHash()),key->null,c->prepared.apply(original,value,new Current(1,original.inputHash(),original.expectedRootRevision(),null,true,false,null,value.resultHash(),original.attemptId(),false,"EXTERNAL_RESULT_CONFIRMED"),manual));}
    @Test void ConfirmedProviderUsesFullNativeVersionAndMemberWriterInAllFourCategories(){
        for(String category:List.of("agent","skill","workflow","model")){
            boot(category,true,true);int beforeCompletions=completions;var value=retainedValue();var prepared=handler.prepareForManual(original,value);assertEquals(new Applied(null,null),applyPrepared(prepared,value,true));assertEquals(beforeCompletions+1,completions);assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals("fictional-entity",f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals(2,f.sends);assertEquals(1,new JdbcJobRecords(f.source).byId(DEDUP_ROOT).callCount());assertEquals("running",new JdbcResultState(f.source).root(DEDUP_ROOT).state());assertEquals("occupied",new JdbcWorkerState(f.source).slot().state());
        }
    }
    @Test void ConfirmedAndAutomaticPreparedModesCannotBeInterchangedOrReplaceOriginalResult(){
        boot("agent",true,true);var value=retainedValue();var manual=handler.prepareForManual(original,value);var automatic=handler.prepare(original,value);assertThrows(RuntimeException.class,()->applyPrepared(manual,value,false));assertThrows(RuntimeException.class,()->applyPrepared(automatic,value,true));var different=new Validated(value.kind(),value.payloadJson(),"d".repeat(64),value.inputHash());assertThrows(RuntimeException.class,()->applyPrepared(manual,different,true));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type IN ('catalog_version_judgment','entity_decision')",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(2,f.sends);
    }
    @Test void ConfirmedPreparationCannotTurnMissingOrChangedNativeVersionProofIntoAWrite(){
        boot("agent",true,false);assertThrows(RuntimeException.class,()->handler.prepareForManual(original,retainedValue()));boot("agent",true,true);var value=retainedValue();var manual=handler.prepareForManual(original,value);f.jdbc.update("UPDATE source_run_items SET evidence_json='{}' WHERE id='fictional-current-item'");assertThrows(RuntimeException.class,()->applyPrepared(manual,value,true));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type IN ('catalog_version_judgment','entity_decision')",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(2,f.sends);
    }

}
