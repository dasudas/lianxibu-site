package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.service.articles.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.node.ObjectNode;

/** Actual native new-entity and permanent original version triggers; no analysis call or allocation is made. */
class CatalogDayAnalysisEligibilityTest extends CatalogDayEntityEvidenceTest {
    CatalogDayAnalysisEligibilityService eligibility(Fixture f){
        var original=new CatalogDayAnalysisCommitPermitService(evidence(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)),f.graph.permits(),new JdbcSourceDayDedupClosures(f.f.source),f.f.tx,f.f.category);
        var current=new EntityInputEvidenceService(new JdbcEntityDedup(f.f.source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.f.source)));
        return new CatalogDayAnalysisEligibilityService(original,new CatalogEntityAnalysisHeaderService(f.f.source,f.f.tx,f.f.category),current,f.graph.plans(),new CatalogVersionJudgmentLedger(new JdbcCatalogVersionJudgments(f.f.source),f.f.category),new JdbcFrozenSourcePlans(f.f.source),new JdbcJobRecords(f.f.source),f.f.tx,f.f.category);
    }
    class NewFixture extends Fixture {
        @Override void beforeCurrentCollectorRun(){super.beforeCurrentCollectorRun();f.jdbc.update("DELETE FROM analyzed_entities");}
        @Override void modelItem(ObjectNode item){item.put("decision","new").putNull("candidate_entity_id").put("reanalyze_recommended",false);}
        @Override void terminal(String category){super.terminal(category);f.jdbc.execute("ALTER TABLE analyzed_entities ADD created_at "+(mysql()?"DATETIME(6)":"TIMESTAMP(6)"));f.jdbc.execute("ALTER TABLE analyzed_entities ADD aliases_json "+(mysql()?"JSON":"CLOB"));}
    }
    @Test void FourCategoriesActualNewEntityOriginalDayCanSelectGenerationOneWithoutAllocating(){
        for(String category:List.of("agent","skill","workflow","model")){
            var f=new NewFixture();f.terminal(category);f.completeSource();var service=eligibility(f);var captured=service.capture(day);var selected=captured.selected().getFirst();assertEquals(1,captured.selected().size());assertEquals(CatalogDayAnalysisEligibilityService.Trigger.NEW_ENTITY,selected.trigger());assertEquals(0,selected.header().generation());assertEquals("pending",selected.header().state());assertEquals(1,selected.generation());assertNull(selected.intentId());assertEquals(CatalogDayAnalysisEligibilityService.logicalKey(category,selected.entity().entityId()),selected.logicalKey());int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertEquals(captured.selected(),f.write(()->service.verify(captured)));assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(0,f.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(2,f.f.sends);
        }
    }
    @Test void ActualPositiveFirstJudgmentJoinsOriginalPendingGenerationAndPreservesAliasBodies(){
        var outer=new CatalogDayAliasEvidenceTest(){@Override boolean mysql(){return CatalogDayAnalysisEligibilityTest.this.mysql();}};var f=outer.new AliasFixture();f.terminal("agent");f.completeSource();var service=eligibility(f);var captured=service.capture(day);assertEquals(1,captured.selected().size());var selected=captured.selected().getFirst();assertEquals(CatalogDayAnalysisEligibilityService.Trigger.VERSION_RECOMMENDED,selected.trigger());assertEquals(2,selected.generation());assertEquals(2,selected.entity().members().size());assertEquals(1,selected.judgments().size());assertEquals(2,selected.entity().members().stream().map(CatalogDayEntityEvidenceService.Evidence::identityKey).distinct().count());assertEquals(selected.intentId(),JdbcFrozenInputs.stableId("intent",selected.logicalKey()+":2"));int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertEquals(captured.selected(),f.write(()->service.verify(captured)));assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(2,f.f.sends);
    }
    @Test void ActualDeclinedVersionCannotTriggerAnotherAnalysis(){
        var f=new Fixture();f.terminal("skill");var payload=(ObjectNode)CanonicalJson.parse(f.raw).get("payload");for(var item:payload.get("items"))((ObjectNode)item).put("reanalyze_recommended",false);f.raw=ResultApplicationService.providerEnvelope(f.application.input(),JdbcFrozenInputs.canonical(payload));f.completeSource();var service=eligibility(f);var captured=service.capture(day);assertTrue(captured.selected().isEmpty());assertEquals("generation_settled",captured.excluded().getFirst().reason());assertTrue(f.write(()->service.verify(captured)).isEmpty());assertEquals(1,f.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(1,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(2,f.f.sends);
    }
    @Test void FreshEntityCurrentMemberAndFirstLedgerDamageOrWrongRolesRejectWholeCaller(){
        var f=new Fixture();f.terminal("workflow");f.completeSource();var service=eligibility(f);var captured=service.capture(day);assertThrows(RuntimeException.class,()->service.verify(captured));assertThrows(RuntimeException.class,()->snapshot(f,()->service.verify(captured)));assertThrows(RuntimeException.class,()->f.write(()->eligibility(f).verify(captured)));assertThrows(RuntimeException.class,()->snapshot(f,()->service.capture(day)));long revision=f.f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class);assertThrows(RuntimeException.class,()->f.write(()->{f.f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1");return service.verify(captured);}));assertEquals(revision,f.f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));
        f.f.jdbc.update("UPDATE source_run_items SET evidence_hash=? WHERE id='fictional-current-item'",new byte[32]);assertThrows(RuntimeException.class,()->f.write(()->service.verify(captured)));f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='catalog_version_judgment'",new byte[32]);assertThrows(RuntimeException.class,()->service.captureOriginal(day));assertEquals(2,f.f.sends);
    }
    @Test void ClosedCategoryMandatoryConstructionDoesNoIo(){
        var original=mock(CatalogDayAnalysisCommitPermitService.class);var entities=mock(CatalogEntityAnalysisHeaderService.class);var members=mock(EntityInputEvidenceService.class);var plans=mock(FrozenSourceStagePlanService.class);var ledger=mock(CatalogVersionJudgmentLedger.class);var owners=mock(JdbcFrozenSourcePlans.class);var records=mock(JdbcJobRecords.class);var manager=mock(PlatformTransactionManager.class);for(String category:List.of("agent","skill","workflow","model"))assertNotNull(new CatalogDayAnalysisEligibilityService(original,entities,members,plans,ledger,owners,records,manager,category));verifyNoInteractions(original,entities,members,plans,ledger,owners,records,manager);assertThrows(RuntimeException.class,()->new CatalogDayAnalysisEligibilityService(original,entities,members,plans,ledger,owners,records,manager,"news_tech"));
    }
    @Test void ExistingPendingIntentNaturalIdentityDamageCannotCreateAReplacement(){
        var f=new Fixture();f.terminal("model");f.completeSource();var service=eligibility(f);var captured=service.capture(day);String intent=captured.selected().getFirst().intentId();int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertEquals(1,f.f.jdbc.update("UPDATE jobs SET record_identity_hash=? WHERE id=?",new byte[32],intent));assertThrows(RuntimeException.class,()->f.write(()->service.verify(captured)));assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(2,f.f.sends);
    }
}
