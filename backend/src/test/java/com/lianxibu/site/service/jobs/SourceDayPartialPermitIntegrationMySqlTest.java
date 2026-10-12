package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

/** Actual complete day registration and native partial authority; failed P1 terminal is fictional genesis. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayPartialPermitIntegrationMySqlTest {
    final LocalDate day=LocalDate.of(2026,1,1);final Instant registered=Instant.parse("2026-01-01T16:10:00Z"),p1At=Instant.parse("2026-01-01T17:20:00Z");
    SourceDedupResolvedStageResultTest original;SourceAnalysisProofGraph graph;DataSourceTransactionManager manager;
    SourceDayCohortSealService cohorts;SourceDayRunCoordinatorService coordinator;com.lianxibu.site.domain.sources.SourceDayRunRegistration.Api runs;
    SourceDayNewsDedupBarrierService barriers;SourceDayNewsValuePermitService permits;FrozenSourceDedupDependency own;
    String run(){return JdbcFrozenInputs.stableId("source-day-run",day+"\nfictional-source");}
    boolean newPreparationAllowed(){return false;}
    void setup()throws Exception{setup(true);}
    void setup(boolean partial)throws Exception{
        original=new SourceDedupResolvedStageResultTest(){
            @Override GenerationJdbcStoreTest database(){var db=new GenerationJdbcStoreMySqlTest();db.clock=Clock.fixed(p1At,ZoneOffset.UTC);return db;}
            @Override String runId(){return run();}
            @Override boolean initializeRegisteredRun(String id){register(id);return true;}
        };original.setup(1);if(partial){original.terminal(0,"failed");assertNotNull(graph.dedupSeals().seal(original.ref));}else original.applied(0);var proof=original.f.read(()->graph.dedupProofs().readCommitted(original.ref));assertNotNull(proof);assertEquals(p1At,graph.dedupObserver().capture(original.ref));own=FrozenSourceDedupDependency.create(proof.proof(),p1At,cutoff());
        var configuration=new SourceDayNewsDedupConfiguration();barriers=configuration.createBarrier(original.f.db.source,manager,cohorts,configuration.createProgress(manager,runs,graph),Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));
        var admission=new SourceDayRunClaimAdmissionConfiguration().create(original.f.db.source,manager,new SourceDayCollectionAdmissionService(cohorts));permits=new SourceDayNewsValuePermitService(barriers,admission,new JdbcSourceDayDedupClosures(original.f.db.source),graph.dedupProofs(),graph.dedupObserver(),graph.dependencies(),graph.dedupPermits(),manager);
    }
    Instant cutoff(){return p1At.plusSeconds(120);}
    void register(String id){
        var f=original.f;var sql=f.db.jdbc;manager=new DataSourceTransactionManager(f.db.source);
        for(String field:List.of("revision BIGINT","config_revision BIGINT","activation_stage VARCHAR(32)","state VARCHAR(32)","config_valid_until DATETIME(6)","draft_config_json JSON","effective_config_json JSON"))sql.execute("ALTER TABLE sources ADD "+field);
        sql.update("UPDATE sources SET revision=2,config_revision=3,activation_stage='production',state='enabled',config_valid_until=?,effective_config_json=?",java.sql.Timestamp.from(registered.plusSeconds(86400)),"{\"valid_until\":\"2026-01-02T16:10:00.000000Z\",\"parser_version\":\"fictional-parser-v1\",\"fixture\":\"fictional\"}");
        for(String field:List.of("created_at DATETIME(6)","window_start DATETIME(6)","window_end DATETIME(6)","base_run_id VARCHAR(64)","fetch_mode VARCHAR(32)","collection_reason VARCHAR(32)"))sql.execute("ALTER TABLE source_runs ADD "+field);
        for(String field:List.of("principal VARCHAR(80)","operation VARCHAR(191)","request_key VARCHAR(128)","request_hash BINARY(32)","status_code INT","response_json JSON","expires_at DATETIME(6)","actor_id VARCHAR(64)","key_hash BINARY(32)","original_status VARCHAR(32)","current_status VARCHAR(32)","result_ref_json JSON","created_at DATETIME(6)","revision BIGINT","request_key_raw VARCHAR(128)"))sql.execute("ALTER TABLE request_receipts ADD "+field);
        var gate=mock(OperationalInputGate.class);when(gate.newRunAllowed(anyString())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return true;});
        cohorts=new SourceDayCohortSealConfiguration().create(f.db.source,manager,Clock.fixed(registered,ZoneOffset.UTC));runs=new SourceDayRunRegistrationConfiguration().create(f.db.source,manager,gate,Clock.fixed(registered.plusSeconds(1),ZoneOffset.UTC));coordinator=new SourceDayRunCoordinatorConfiguration().create(cohorts,runs);
        var fullDay=coordinator.open(day);assertEquals(5,fullDay.plan().cohorts().size());assertEquals(id,coordinator.reserve(fullDay,"fictional-source").request().runId());
        graph=new SourceAnalysisProofGraph(f.db.source,manager,Clock.fixed(p1At,ZoneOffset.UTC),this::newPreparationAllowed);f.parts=graph.parts();f.plans=graph.plans();f.p1.plans=graph.plans();f.p1.universes=graph.universes();f.p1.collections=graph.collections();f.p1.preparation=f.p1.service();
        var value=mock(NewsValueSourcePlanPreparationService.class);var preparation=new SourcePlanPreparationCoordinator(f.p1.preparation,value,graph.parts(),graph.handoffs(),graph.dedupProofs(),new JdbcFrozenSourcePlans(f.db.source),f.db.writes,manager,f.clock,()->true,graph.dedupPermits());f.step=NewsSourcePreparationConfiguration.parentStep(f.db.source,manager,f.clock,preparation,graph);
    }
    FrozenSourceDayDedupDependency verify(SourceDayNewsValuePermitService.Captured captured){return new TransactionTemplate(manager).execute(s->permits.verify(captured,cutoff()));}
    @Test void actualPartialOriginalP1EntersTheOriginalCompleteDayAndReplaysWithoutCurrentSources()throws Exception{
        setup();var captured=permits.capture(own.toJson(),cutoff());assertNotNull(captured);assertTrue(verify(captured).originalBatchEligible());assertEquals(SourceDedupBarrier.State.PARTIAL,barriers.find(day).closure().entries().getFirst().progress().state());
        var ro=new TransactionTemplate(manager);ro.setReadOnly(true);assertEquals(captured.dependency().json(),ro.execute(s->permits.verifyObserved(captured,cutoff())).json());int rows=original.f.db.count("1=1");original.f.db.jdbc.execute("DROP TABLE sources");var replay=permits.captureExisting(captured.dependency().toJson(),own.toJson(),cutoff());assertEquals(captured.dependency().json(),verify(replay).json());assertEquals(rows,original.f.db.count("1=1"));
    }
    @Test void lossOfActualRegisteredRunReceiptCannotGrantDayPermission()throws Exception{
        setup();var captured=permits.capture(own.toJson(),cutoff());assertNotNull(captured);original.f.db.jdbc.update("DELETE FROM request_receipts WHERE actor_id='source-day-register'");assertThrows(RuntimeException.class,()->verify(captured));assertThrows(RuntimeException.class,()->permits.captureExisting(captured.dependency().toJson(),own.toJson(),cutoff()));
    }
    @Test void damagedActualPartialSealOrNativeRevisionCannotReuseTheCapturedDay()throws Exception{
        setup();var captured=permits.capture(own.toJson(),cutoff());assertNotNull(captured);original.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",original.plan.chunks().getFirst().rootId());assertThrows(RuntimeException.class,()->verify(captured));
        setup();var second=permits.capture(own.toJson(),cutoff());original.f.db.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcSourceDedupPartialReceipts.receiptId(original.ref.ownerId()));assertThrows(RuntimeException.class,()->verify(second));
    }
}
