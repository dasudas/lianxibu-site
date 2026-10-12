package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Complete original two-source Graph/readiness/worker/factory/ledger/member path. */
class CatalogVersionCrossSourceApplicationTest extends CatalogVersionCrossSourceFixture {
    CatalogVersionJudgmentLedger.Stored first(GenerationFlowService.Frozen root){
        var node=CanonicalJson.parse(root.snapshotJson());var input=FrozenApplicationContext.fromJson(node.get(FrozenApplicationContext.FIELD)).input(root.ownerId(),"fictional-first-read",root.inputHash(),0);var entry=FrozenCatalogVersionProjections.read(node.get(FrozenCatalogVersionProjections.FIELD),input).entries().values().iterator().next();
        var reads=new TransactionTemplate(f.tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return reads.execute(s->new CatalogVersionJudgmentLedger(new JdbcCatalogVersionJudgments(f.source),f.category).read(entry.judgmentKey()).orElseThrow());
    }
    @Test void FourCategoriesTwoAlreadyFrozenSourcesApplyOnePendingAnalysisIntentAndBothOriginalResults(){
        for(String category:List.of("agent","skill","workflow","model")){
            functional(category,false,false,true);var firstRoot=graph.coordinator().prepareDedup(collection.intentId(),RUN,0).root();prepareSecondOriginalSource();String original=second.root().snapshotJson();assertEquals(Status.APPLIED,apply().status());var first=first(firstRoot);
            sendSecond();assertEquals(Status.APPLIED,apply().status());var later=first(second.root());assertNotEquals(first.judgmentKey(),later.judgmentKey());assertEquals(first.intentId(),later.intentId());assertEquals(2L,later.generation());
            assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Long.class));assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='generation_intent' AND task_type=?",Integer.class,category+"_analysis"));
            assertEquals(original,new JdbcFrozenInputs(f.source).read(second.root().ownerId(),second.root().inputHash()));assertNotNull(new JdbcResultState(f.source).root(second.root().ownerId()).resultHash());assertEquals("APPLIED",new JdbcResultState(f.source).root(second.root().ownerId()).application());assertEquals("free",new JdbcWorkerState(f.source).slot().state());assertEquals(3,f.sends);
            assertEquals(1,new JdbcJobRecords(f.source).byId(second.root().ownerId()).callCount());assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE id IN ('fictional-current-item',?) AND entity_id='fictional-entity'",Integer.class,SECOND_ITEM));
            int jobs=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertEquals(Status.APPLIED,apply().status());assertEquals(jobs,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(3,f.sends);
        }
    }
    @Test void EarlierDeclineDoesNotPreventDifferentSourceFirstPositiveWithCurrentCas(){
        functional("agent",false,false,true);prepareSecondOriginalSource();var payload=(ObjectNode)CanonicalJson.parse(raw).get("payload");((ObjectNode)payload.get("items").get(0)).put("reanalyze_recommended",false);raw=ResultApplicationService.providerEnvelope(application.input(),JdbcFrozenInputs.canonical(payload));assertEquals(Status.APPLIED,apply().status());assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Long.class));
        sendSecond();assertEquals(Status.APPLIED,apply().status());assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Long.class));assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(3,f.sends);
    }
    @Test void SecondSourceDownstreamFailureRetainsResponseAndRollsBackItsFirstLedgerAndMemberThenSameSentAttemptRetries(){
        functional("skill",false,false,true);prepareSecondOriginalSource();assertEquals(Status.APPLIED,apply().status());sendSecond();long revision=f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class);
        f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_second_source_failure CHECK(id<>'"+JdbcFrozenInputs.stableId("member-stage","member-stage:"+second.root().ownerId())+"')");
        assertThrows(RuntimeException.class,this::apply);assertEquals(revision,f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Long.class));assertNotNull(new JdbcResultState(f.source).root(application.input().rootId()).resultHash());assertNull(f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id=?",String.class,SECOND_ITEM));
        f.jdbc.execute(mysql()?"ALTER TABLE jobs DROP CHECK fictional_second_source_failure":"ALTER TABLE jobs DROP CONSTRAINT fictional_second_source_failure");assertEquals(Status.APPLIED,apply().status());assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(3,f.sends);
    }
}
