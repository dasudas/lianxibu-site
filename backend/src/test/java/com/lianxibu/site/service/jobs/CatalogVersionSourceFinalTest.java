package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.Status;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Full actual version source application through final projection, permanent receipt and first observation. */
class CatalogVersionSourceFinalTest extends CatalogVersionCrossSourceFixture {
    FrozenSourceStagePlan.Reference reference;
    void terminal(String category){
        functional(category,false,false,true);String text=mysql()?"MEDIUMTEXT":"CLOB",json=mysql()?"JSON":"CLOB";
        for(String column:List.of("dedup_summary "+text,"analysis_summary "+text,"analysis_state VARCHAR(32)","coverage_json "+json))f.jdbc.execute("ALTER TABLE source_runs ADD "+column);
        f.jdbc.update("UPDATE source_runs SET analysis_state='waiting_input',coverage_json='{}'");var plan=graph.coordinator().prepareDedup(collection.intentId(),RUN,0).plan();var reads=new TransactionTemplate(f.tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reference=reads.execute(s->graph.plans().findImmutableReference(plan.reference().ownerId(),plan.logicalKey(),plan.generation(),plan.stage(),plan.scope().businessDate()));
    }
    CatalogSourceFinalizationService.Committed committed(){var reads=new TransactionTemplate(f.tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return reads.execute(s->graph.finalizer().readCommitted(reference));}
    String sourceValue(String name){return f.jdbc.queryForObject("SELECT "+name+" FROM source_runs WHERE id=?",String.class,RUN);}
    @Test void FourCategoriesActualOriginalSourceAppliesAndFinalizesItsSamePermanentProofThenRestarts(){
        for(String category:List.of("agent","skill","workflow","model")){
            terminal(category);String collector=JdbcFrozenInputs.canonical(CanonicalJson.parse(sourceValue("result_json")));assertNull(graph.finalizer().finish(reference));assertNull(graph.observer().capture(reference));assertEquals(Status.APPLIED,apply().status());
            var receipt=graph.finalizer().finish(reference);assertNotNull(receipt);var proof=committed();assertEquals(receipt.json(),proof.receipt().json());assertEquals(category,CanonicalJson.parse(proof.result().json()).get("category").asText());assertEquals(1,CanonicalJson.parse(proof.result().json()).get("eligible_count").asInt());
            assertEquals(proof.result().summary(),sourceValue("dedup_summary"));assertEquals("",sourceValue("analysis_summary"));assertEquals("succeeded",sourceValue("analysis_state"));assertEquals(collector,JdbcFrozenInputs.canonical(CanonicalJson.parse(sourceValue("result_json"))));assertEquals("{}",JdbcFrozenInputs.canonical(CanonicalJson.parse(sourceValue("coverage_json"))));
            Instant at=graph.observer().capture(reference);assertNotNull(at);assertFalse(at.isBefore(proof.minimumObservationTime()));int jobs=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);long revision=f.jdbc.queryForObject("SELECT revision FROM source_runs WHERE id=?",Long.class,RUN);
            f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-current-item'");f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}' WHERE id=?",RUN);graph();assertEquals(receipt.json(),graph.finalizer().finish(reference).json());assertEquals(at,graph.observer().capture(reference));assertEquals(jobs,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(revision,f.jdbc.queryForObject("SELECT revision FROM source_runs WHERE id=?",Long.class,RUN));assertEquals(2,f.sends);
        }
    }
    @Test void ActualFinalReceiptFailureRollsBackProjectionAndOriginalIntentThenRealLostAckRecovers(){
        terminal("skill");assertEquals(Status.APPLIED,apply().status());long revision=f.jdbc.queryForObject("SELECT revision FROM source_runs WHERE id=?",Long.class,RUN);
        f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_full_final_failure CHECK(target_type IS NULL OR target_type<>'catalog_source_final_result')");assertThrows(RuntimeException.class,()->graph.finalizer().finish(reference));assertNull(sourceValue("dedup_summary"));assertEquals(revision,f.jdbc.queryForObject("SELECT revision FROM source_runs WHERE id=?",Long.class,RUN));assertEquals("queued",f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,reference.ownerId()));assertEquals("APPLIED",new JdbcResultState(f.source).root(application.input().rootId()).application());
        f.jdbc.execute(mysql()?"ALTER TABLE jobs DROP CHECK fictional_full_final_failure":"ALTER TABLE jobs DROP CONSTRAINT fictional_full_final_failure");assertNotNull(graph.archives().stage(reference));f.tx.ackLost=true;var receipt=graph.finalizer().finish(reference);assertNotNull(receipt);assertEquals(receipt.json(),graph.finalizer().finish(reference).json());assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_source_final_result'",Integer.class));assertEquals(revision+1,f.jdbc.queryForObject("SELECT revision FROM source_runs WHERE id=?",Long.class,RUN));assertEquals(2,f.sends);
    }
    @Test void ActualObserverFailureDoesNotReapplyTheCommittedSourceAndBadPermanentArchiveRejects(){
        terminal("workflow");assertEquals(Status.APPLIED,apply().status());var receipt=graph.finalizer().finish(reference);long revision=f.jdbc.queryForObject("SELECT revision FROM source_runs WHERE id=?",Long.class,RUN);
        f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_full_observer_failure CHECK(target_type IS NULL OR target_type<>'catalog_final_availability')");assertThrows(RuntimeException.class,()->graph.observer().capture(reference));assertEquals(receipt.json(),graph.finalizer().finish(reference).json());assertEquals(revision,f.jdbc.queryForObject("SELECT revision FROM source_runs WHERE id=?",Long.class,RUN));
        f.jdbc.execute(mysql()?"ALTER TABLE jobs DROP CHECK fictional_full_observer_failure":"ALTER TABLE jobs DROP CONSTRAINT fictional_full_observer_failure");assertNotNull(graph.observer().capture(reference));f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],receipt.position().receiptId());assertThrows(RuntimeException.class,this::committed);assertThrows(RuntimeException.class,()->graph.observer().capture(reference));assertEquals(2,f.sends);
    }
}
