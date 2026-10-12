package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.JdbcEntityAnalysisState;
import com.lianxibu.site.service.articles.EntityAnalysisStateService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Native original batch INPUT and version writes; the new model decisions remain fictional pure values. */
class CatalogVersionBatchJudgmentWriterTest extends CatalogVersionBatchPreflightTest {
    Map<String,CatalogVersionQualificationProjection.Projection> projections;
    void seedMany(int count,boolean differentVersions){
        projections=new TreeMap<>();
        for(int n=0;n<count;n++){
            String id=String.format(Locale.ROOT,"fictional-batch-%03d",n);var body=current.deepCopy();
            if(differentVersions&&n>0){body.put("version","v3");((ObjectNode)body.get("evidence").get(0)).put("revision","v3");}
            f.jdbc.update("INSERT INTO source_run_items SELECT ?,run_id,source_id,revision,category,created_at,terminal_state,state,projection_decision,effective_valid,?,?,entity_id,? FROM source_run_items WHERE id='fictional-current-item'",id,JdbcFrozenInputs.canonical(body),HexFormat.of().parseHex(CatalogPreviousAnalysisTest.Fixture.sha(CanonicalJson.bytes(body))),"fictional-batch-source-"+n);
            projections.put(id,service.readProjection(f.category,"fictional-entity","fictional-analysis",id,f.clock.instant()).orElseThrow());
        }
        var context=new FrozenCatalogVersionContextTest().context(input(projections.values().iterator().next()).stage());
        var ids=context.putArray("item_ids");context.putArray("eligible_high_value_item_ids");var candidates=context.putArray("candidates_by_item");
        var eligible=context.putArray("version_eligible_item_ids");var qs=context.putArray("catalog_version_qualifications");
        projections.forEach((id,p)->{
            ids.add(id);candidates.addObject().put("item_id",id).putArray("entity_ids").add(p.entityId()).add("fictional-other-entity");
            var typed=p.resultConstraint();if(typed.versionChanged())eligible.add(id);var q=qs.addObject().put("item_id",id).put("entity_id",p.entityId()).put("version_changed",typed.versionChanged());
            q.putObject("previous_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",typed.previousProofHash());q.putObject("new_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",typed.newProofHash());
        });
        var ctx=FrozenApplicationContext.fromJson(context);var temp=ctx.input(root,"fictional-dedup-attempt","c".repeat(64),0);
        var snapshot=JsonMapper.shared().createObjectNode().put("schema_version","input-v1").put("logical_key","fictional-catalog-dedup-key").put("generation",1).put("stage",f.category+"_dedup").put("cutoff",projections.values().iterator().next().toJson().get("cutoff").asText());
        snapshot.set(FrozenApplicationContext.FIELD,ctx.toJson());snapshot.set(FrozenCatalogVersionProjections.FIELD,FrozenCatalogVersionProjections.create(projections.values(),temp).toJson());
        originalJson=JdbcFrozenInputs.canonical(snapshot);String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot);original=ctx.input(root,"fictional-dedup-attempt",hash,0);
        f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-catalog-input-seed",root,hash),k->null,a->{
            var records=new JdbcJobRecords(f.source);records.ensureIntent(new JdbcJobRecords.Intent("fictional-catalog-dedup-intent","fictional-catalog-dedup-key",1,f.category+"_dedup",LocalDate.of(2026,1,1)),f.clock.instant());
            records.createRoot(new JdbcJobRecords.Root(root,"fictional-catalog-dedup-intent","fictional-catalog-dedup-key",1,0,f.category+"_dedup",original.stage().priority()),f.clock.instant());new JdbcFrozenInputs(f.source).save(root,originalJson,hash,f.clock.instant());f.jdbc.update("UPDATE jobs SET input_cutoff_at=? WHERE id=?",java.sql.Timestamp.from(f.clock.instant()),root);return true;
        });
    }
    Validated decisions(boolean recommend,boolean disagree){
        var payload=JsonMapper.shared().createObjectNode().put("kind","dedup").put("dedup_result_version","2").put("summary","Fictional complete batch decisions");var items=payload.putArray("items");int n=0;
        for(var p:projections.values())items.add(payload(p,disagree?n++>0:recommend).get("items").get(0));return result(original,payload);
    }
    CatalogVersionBatchJudgmentWriter writer(CatalogVersionBatchPreflightService batches){return new CatalogVersionBatchJudgmentWriter(f.source,batches.permits(),new EntityAnalysisStateService(new JdbcEntityAnalysisState(f.source)),f.category,f.clock);}
    CatalogVersionBatchJudgmentWriter.Outcome apply(CatalogVersionBatchJudgmentWriter writer,CatalogVersionBatchJudgmentWriter.Prepared prepared){
        return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-catalog-batch-apply",root,original.inputHash()),k->writer.committed(prepared),a->writer.applyInTransaction(prepared));
    }
    Map<String,String> json(CatalogVersionBatchJudgmentWriter.Outcome outcome){var values=new TreeMap<String,String>();outcome.judgments().forEach((id,value)->values.put(id,value.json()));return values;}
    void counts(int generation,int judgments,int intents){assertEquals(generation,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(judgments,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(intents,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='generation_intent' AND task_type=?",Integer.class,f.category+"_analysis"));assertEquals(1,f.sends);}
    @Test void FourCategoryBatchAllocatesOneGenerationAndRetainsOriginalLedgerOnReplay(){
        for(String cat:List.of("agent","skill","workflow","model")){
            initialize(cat,false);seedMany(2,false);var batches=batches();var writer=writer(batches);var prepared=writer.prepare(batches.capture(original),decisions(true,false));var outcome=apply(writer,prepared);
            assertEquals(2,outcome.judgments().size());assertEquals(2,outcome.reanalysisItems().size());var stored=outcome.judgments().values().iterator().next();assertEquals(2L,stored.generation());counts(2,1,2);
            assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='logical_task'",Integer.class));assertEquals("fictional-analysis-key",new JdbcJobRecords(f.source).byId(stored.intentId()).logicalKey());
            f.jdbc.update("DELETE FROM source_run_items WHERE id LIKE 'fictional-batch-%'");assertEquals(json(outcome),json(apply(writer,prepared)));
            var after=batches.capture(original);assertTrue(after.permissions().isEmpty());assertEquals(json(outcome),json(apply(writer,writer.prepare(after,decisions(false,false)))));counts(2,1,2);
        }
    }
    @Test void ActualHundredKnownSameEntityMembersUseOneGenerationAndCompleteOriginalBatch(){
        seedMany(100,false);var batches=batches();var writer=writer(batches);var prepared=writer.prepare(batches.capture(original),decisions(true,false));var result=apply(writer,prepared);
        assertEquals(100,result.judgments().size());assertTrue(result.judgments().containsKey("fictional-batch-099"));assertEquals(100,result.reanalysisItems().size());assertEquals(1,result.judgments().values().stream().map(CatalogVersionJudgmentLedger.Stored::judgmentKey).distinct().count());counts(2,1,2);
    }
    @Test void TwoDifferentQualifiedVersionsOfOneEntityAllocateOneIntentWithTwoFirstJudgments(){
        seedMany(2,true);var batches=batches();var writer=writer(batches);var outcome=apply(writer,writer.prepare(batches.capture(original),decisions(true,false)));
        assertEquals(2,outcome.judgments().values().stream().map(CatalogVersionJudgmentLedger.Stored::judgmentKey).distinct().count());assertEquals(1,outcome.judgments().values().stream().map(CatalogVersionJudgmentLedger.Stored::intentId).distinct().count());counts(2,2,2);
    }
    @Test void OriginalFirstSortedMemberDeclineWinsOverLaterDuplicatePositiveRecommendations(){
        seedMany(2,false);var batches=batches();var writer=writer(batches);var originalOutcome=apply(writer,writer.prepare(batches.capture(original),decisions(false,true)));
        assertTrue(originalOutcome.reanalysisItems().isEmpty());assertTrue(originalOutcome.judgments().values().stream().allMatch(s->s.verdict().equals("changed_declined")));counts(1,1,1);
        f.jdbc.update("DELETE FROM source_run_items WHERE id LIKE 'fictional-batch-%'");assertEquals(json(originalOutcome),json(apply(writer,writer.prepare(batches.capture(original),decisions(true,false)))));counts(1,1,1);
    }
    @Test void BadLastCurrentRawProofRejectsWholeHundredBeforeAnyReservationOrLedgerWrite(){
        seedMany(100,false);var batches=batches();var writer=writer(batches);var prepared=writer.prepare(batches.capture(original),decisions(true,false));long revision=f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class);
        f.jdbc.update("UPDATE source_run_items SET evidence_json='{}' WHERE id='fictional-batch-099'");assertThrows(RuntimeException.class,()->apply(writer,prepared));counts(1,0,1);assertEquals(revision,f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));
    }
    @Test void LastLedgerAppendFailureRollsBackEntireAllocationAndAllFirstJudgments(){
        seedMany(2,true);var batches=batches();var writer=writer(batches);var prepared=writer.prepare(batches.capture(original),decisions(true,false));String last=projections.values().stream().map(CatalogVersionQualificationProjection.Projection::judgmentKey).sorted().toList().getLast();
        f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_batch_append_failure CHECK(id<>'"+JdbcCatalogVersionJudgments.id(last)+"')");assertThrows(RuntimeException.class,()->apply(writer,prepared));counts(1,0,1);
        f.jdbc.execute(mysql()?"ALTER TABLE jobs DROP CHECK fictional_batch_append_failure":"ALTER TABLE jobs DROP CONSTRAINT fictional_batch_append_failure");assertEquals(2,apply(writer,prepared).reanalysisItems().size());counts(2,2,2);
    }
    @Test void UnknownResultAndForeignPreparedNeverAllocateAndAckUnknownReadsWholeOriginalOutcome(){
        seedMany(2,false);var batches=batches();var writer=writer(batches);var batch=batches.capture(original);var model=decisions(true,false);var prepared=writer.prepare(batch,model);
        var foreign=writer(batches);assertThrows(RuntimeException.class,()->apply(foreign,prepared));counts(1,0,1);f.tx.ackLost=true;var result=apply(writer,prepared);assertEquals(json(result),json(apply(writer,prepared)));counts(2,1,2);
        initialize("agent",false);seed(false,true);batches=batches();writer=writer(batches);var payload=payload(previous,false);var row=(ObjectNode)payload.get("items").get(0);row.put("decision","needs_review").putNull("candidate_entity_id").putNull("version_changed").putNull("previous_evidence_ref").putNull("new_evidence_ref");var empty=apply(writer,writer.prepare(batches.capture(original),result(original,payload)));assertTrue(empty.judgments().isEmpty());counts(1,0,1);
    }
    void NativeConcurrentBatchCallersKeepOneOriginalGenerationAndCompleteReceipt()throws Exception{
        seedMany(2,false);var batches=batches();var writer=writer(batches);var prepared=writer.prepare(batches.capture(original),decisions(true,false));var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){var one=pool.submit(()->{start.await();return apply(writer,prepared);});var two=pool.submit(()->{start.await();return apply(writer,prepared);});start.countDown();assertEquals(json(one.get(10,TimeUnit.SECONDS)),json(two.get(10,TimeUnit.SECONDS)));}counts(2,1,2);
    }
}
