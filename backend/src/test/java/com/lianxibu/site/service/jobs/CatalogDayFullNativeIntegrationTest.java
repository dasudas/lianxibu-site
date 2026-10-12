package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.Status;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Actual registration and full original catalog worker/result/final/day closure; OPS gate and model transport are explicit doubles. */
class CatalogDayFullNativeIntegrationTest {
    final LocalDate day=LocalDate.of(2026,1,1);final Instant registered=Instant.parse("2026-01-01T16:10:00Z"),reserved=registered.plusSeconds(10),completed=registered.plusSeconds(3600);
    boolean mysql(){return false;}
    class Fixture extends CatalogVersionSourceFinalTest {
        SourceDayCohortSealService cohorts;SourceDayRunRegistration.Api runs;SourceDayRunCoordinatorService coordinator;OperationalInputGate gate;SourceDayRunCoordinatorService.Day original;boolean secondEnabled;
        @Override boolean mysql(){return CatalogDayFullNativeIntegrationTest.this.mysql();}
        @Override String sourceRunId(){return JdbcFrozenInputs.stableId("source-day-run",day+"\nfictional-source");}
        @Override CatalogPreviousVersionProfileTest.Fixture fixture(){
            var value=new CatalogPreviousVersionProfileTest.Fixture(){
                @Override Prepare adjustCommand(Prepare command,ObjectNode payload){
                    var c=super.adjustCommand(command,payload);var previousDay=LocalDate.of(2025,12,31);var evidence=c.evidence().stream().map(e->new Evidence(e.id(),e.kind(),previousDay,e.completedAt(),e.bodyHash(),e.bodyJson(),e.effectiveValid(),e.blocked(),e.projectionDecision(),e.references())).toList();
                    return new Prepare(c.logicalKey(),c.generation(),c.stage(),previousDay,c.sourceId(),c.dependencies(),evidence,c.expectedDependencies(),c.explicitSupplement(),c.settingsHash(),c.applicationContext());
                }
                @Override GenerationFlowService preparation(GenerationJdbcStore frozen,RequestBuilder request,ObjectNode payload,Prepare command){var flow=super.preparation(frozen,request,payload,command);jdbc.update("UPDATE source_runs SET business_date=? WHERE id='fictional-run'",command.businessDate().toString());return flow;}
            };value.clock=Clock.fixed(completed,ZoneOffset.UTC);return value;
        }
        @Override void beforeCurrentCollectorRun(){
            String json=mysql()?"JSON":"CLOB",time=mysql()?"DATETIME(6)":"TIMESTAMP(6)";
            for(String column:List.of("revision BIGINT","config_revision BIGINT","activation_stage VARCHAR(32)","state VARCHAR(32)","config_valid_until "+time,"draft_config_json "+json,"effective_config_json "+json))f.jdbc.execute("ALTER TABLE sources ADD "+column);
            String config="{\"parser_version\":\"fictional-parser-v1\",\"valid_until\":\"2026-01-02T16:10:00.000000Z\"}";
            f.jdbc.update(c->{var ps=c.prepareStatement("UPDATE sources SET revision=2,config_revision=1,activation_stage='production',state='enabled',config_valid_until=?,draft_config_json=NULL,effective_config_json=?");ps.setTimestamp(1,java.sql.Timestamp.from(registered.plusSeconds(86400)),Calendar.getInstance(TimeZone.getTimeZone("UTC")));ps.setString(2,config);return ps;});
            f.jdbc.update("INSERT INTO sources(id,category,revision,config_revision,activation_stage,state,config_valid_until,draft_config_json,effective_config_json) SELECT 'fictional-second',category,2,1,?,'enabled',config_valid_until,NULL,effective_config_json FROM sources WHERE id='fictional-source'",secondEnabled?"production":"disabled");
            for(String column:List.of("created_at "+time,"window_start "+time,"window_end "+time,"base_run_id VARCHAR(64)","fetch_mode VARCHAR(32)","collection_reason VARCHAR(32)","dedup_summary "+(mysql()?"MEDIUMTEXT":"CLOB"),"analysis_summary "+(mysql()?"MEDIUMTEXT":"CLOB"),"analysis_state VARCHAR(32)","coverage_json "+json))f.jdbc.execute("ALTER TABLE source_runs ADD "+column);
            for(String column:List.of("principal VARCHAR(128)","operation VARCHAR(64)","request_key VARCHAR(255)","request_hash BINARY(32)","status_code INT","response_json "+json,"expires_at "+time,"actor_id VARCHAR(128)","key_hash BINARY(32)","original_status VARCHAR(32)","current_status VARCHAR(32)","result_ref_json "+json,"created_at "+time,"revision BIGINT","request_key_raw VARCHAR(255)"))f.jdbc.execute("ALTER TABLE request_receipts ADD "+column);
            gate=mock(OperationalInputGate.class);when(gate.newRunAllowed(anyString())).thenReturn(true);cohorts=new SourceDayCohortSealConfiguration().create(f.source,f.tx,Clock.fixed(registered,ZoneOffset.UTC));runs=new SourceDayRunRegistrationConfiguration().create(f.source,f.tx,gate,Clock.fixed(reserved,ZoneOffset.UTC));coordinator=new SourceDayRunCoordinatorConfiguration().create(cohorts,runs);original=coordinator.open(day);assertNotNull(coordinator.reserve(original,"fictional-source"));
        }
        @Override void recordCurrentCollectorRun(String manifest,String result){
            assertEquals(1,f.jdbc.update("UPDATE source_runs SET delivery_state='complete',finished_at=?,manifest_hash=?,fetch_state='succeeded',expected_count=1,accepted_count=1,excluded_count=0,pending_count=0,part_count=1,result_json=? WHERE id=?",java.sql.Timestamp.from(f.clock.instant().minusSeconds(1)),HexFormat.of().parseHex(manifest),result,sourceRunId()));
        }
        @Override void terminal(String category){
            functional(category,false,false,true);var plan=graph.coordinator().prepareDedup(collection.intentId(),sourceRunId(),0).plan();var reads=new TransactionTemplate(f.tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reference=reads.execute(s->graph.plans().findImmutableReference(plan.reference().ownerId(),plan.logicalKey(),plan.generation(),plan.stage(),plan.scope().businessDate()));
        }
        CatalogSourceDayDedupBarrierService barrier(Clock clock){var progress=new CatalogSourceDayDedupProgressService(runs,graph.plans(),graph.finalizer(),graph.observer(),f.tx,f.category);return new CatalogSourceDayDedupBarrierService(cohorts,progress,new JdbcSourceDayDedupClosures(f.source),f.writes,f.tx,clock,f.category);}
        void completeSource(){assertEquals(Status.APPLIED,apply().status());assertNotNull(graph.finalizer().finish(reference));assertEquals(completed,graph.observer().capture(reference));}
        int closures(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='source_day_dedup'",Integer.class);}
    }
    @Test void FourCategoriesActualRegistrationOriginalWorkerAndFullFinalCloseEarlyThenReplay(){
        for(String category:List.of("agent","skill","workflow","model")){
            var fixture=new Fixture();fixture.terminal(category);assertNull(fixture.barrier(Clock.fixed(completed,ZoneOffset.UTC)).close(day));fixture.completeSource();var closure=fixture.barrier(Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)).close(day);assertTrue(closure.closure().early());assertEquals(2,closure.closure().entries().size());var entry=closure.closure().entries().stream().filter(e->e.sourceId().equals("fictional-source")).findFirst().orElseThrow();assertEquals(SourceDedupBarrier.Decision.INCLUDED,entry.decision());assertEquals(completed,entry.progress().availableAt());assertEquals(fixture.committed().result().hash(),entry.progress().proofHash());assertEquals(1,fixture.closures());assertEquals(2,fixture.f.sends);assertEquals(2,fixture.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));
            fixture.f.jdbc.execute("DROP TABLE sources");fixture.f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-current-item'");Clock clock=mock(Clock.class);reset(fixture.gate);assertEquals(closure.closure().json(),fixture.barrier(clock).close(day).closure().json());verifyNoInteractions(clock,fixture.gate);assertEquals(1,fixture.closures());assertEquals(2,fixture.f.sends);
        }
    }
    @Test void WholeNativeCohortCannotShrinkAndDeadlineKeepsMissingOriginalSource(){
        var fixture=new Fixture();fixture.secondEnabled=true;fixture.terminal("agent");fixture.completeSource();assertNull(fixture.barrier(Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(0,fixture.closures());var closure=fixture.barrier(Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).close(day);assertFalse(closure.closure().early());assertEquals(2,closure.closure().entries().size());assertEquals(1,closure.closure().entries().stream().filter(e->e.decision()==SourceDedupBarrier.Decision.INCLUDED).count());assertEquals("not_completed",closure.closure().entries().stream().filter(e->e.sourceId().equals("fictional-second")).findFirst().orElseThrow().reason());assertEquals(1,fixture.closures());assertEquals(2,fixture.f.sends);
    }
    @Test void ActualOriginalRegistrationDamageAndFinalDamageNeverPublishClosure(){
        var fixture=new Fixture();fixture.terminal("workflow");fixture.completeSource();fixture.f.jdbc.update("UPDATE source_runs SET config_version=2 WHERE id=?",fixture.sourceRunId());assertThrows(RuntimeException.class,()->fixture.barrier(Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(0,fixture.closures());fixture.f.jdbc.update("UPDATE source_runs SET config_version=1 WHERE id=?",fixture.sourceRunId());fixture.f.jdbc.update("DELETE FROM request_receipts WHERE receipt_kind='admin_action'");assertThrows(RuntimeException.class,()->fixture.barrier(Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(0,fixture.closures());
        var damaged=new Fixture();damaged.terminal("skill");damaged.completeSource();damaged.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],damaged.committed().receipt().position().receiptId());assertThrows(RuntimeException.class,()->damaged.barrier(Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(0,damaged.closures());assertEquals(2,damaged.f.sends);
    }
    @Test void ActualClosureFailureRollsBackOnlyClosureAndRealLostAckReturnsOneOriginal(){
        var fixture=new Fixture();fixture.terminal("model");fixture.completeSource();fixture.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_day_final_failure CHECK(target_type IS NULL OR target_type<>'source_day_dedup')");assertThrows(RuntimeException.class,()->fixture.barrier(Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(0,fixture.closures());assertNotNull(fixture.committed());assertEquals("APPLIED",new JdbcResultState(fixture.f.source).root(fixture.application.input().rootId()).application());fixture.f.jdbc.execute(mysql()?"ALTER TABLE jobs DROP CHECK fictional_day_final_failure":"ALTER TABLE jobs DROP CONSTRAINT fictional_day_final_failure");fixture.f.tx.ackLost=true;var closure=fixture.barrier(Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)).close(day);assertNotNull(closure);assertEquals(1,fixture.closures());assertEquals(closure.closure().json(),fixture.barrier(mock(Clock.class)).close(day).closure().json());assertEquals(2,fixture.f.sends);
    }
}
