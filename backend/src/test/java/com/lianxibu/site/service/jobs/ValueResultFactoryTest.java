package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.ResultApplicationFactory;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.*;

class ValueResultFactoryTest {
    ValueClassificationConsumerTest base;NewsValueSourcePlanPreparationTest f;Clock clock;ResultJdbcStoreTest.FaultManager tx;
    WorkerAuthorizationService worker;WorkerAuthorizationService.Identity identity;ResultApplicationFactory factory;int sends;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    ValueClassificationConsumerTest consumerFixture(){return new ValueClassificationConsumerTest(){@Override GenerationJdbcStoreTest fixture(){return ValueResultFactoryTest.this.fixture();}};}
    @BeforeEach void setup() throws Exception {
        base=consumerFixture();base.setup();f=base.f;
        if(f.f.source instanceof org.h2.jdbcx.JdbcDataSource)f.f.jdbc.execute("CREATE ALIAS UNHEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.unhex'");
        for(String c:new String[]{"slot_state VARCHAR(16) DEFAULT 'free'","slot_revision BIGINT DEFAULT 0","fencing_token BIGINT DEFAULT 0","job_id VARCHAR(64)","holder VARCHAR(64)","lease_until TIMESTAMP(6)","request_deadline TIMESTAMP(6)","occupied_since TIMESTAMP(6)"})f.f.jdbc.execute("ALTER TABLE worker_lock ADD "+c);
        for(String c:new String[]{"worker_instance_id VARCHAR(64)","dispatch_token BIGINT","request_deadline TIMESTAMP(6)","send_authorized_at TIMESTAMP(6)","slot_released_at TIMESTAMP(6)","release_reason VARCHAR(2048)"})f.f.jdbc.execute("ALTER TABLE jobs ADD "+c);
        clock=Clock.fixed(f.clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);tx=new ResultJdbcStoreTest.FaultManager(f.f.source);
        final DispatchReadinessService[] readiness={null};new DispatchSourcePlanConfigurationTest().context(f.f).run(c->{assertNull(c.getStartupFailure());readiness[0]=c.getBean(DispatchReadinessService.class);});
        worker=new WorkerAuthorizationService(f.f.writes,new WorkerJdbcStore(new JdbcWorkerState(f.f.source),new JdbcJobRecords(f.f.source),"fictional-value-epoch",()->false,readiness[0]::ready,clock),clock,()->true);
        var claim=worker.claim(action("fictional-factory-claim"));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());identity=claim.permit().identity();assertEquals(base.input.rootId(),identity.rootId());
        assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claim.permit(),request->{sends++;return "fictional value response";}).state());factory=new ResultApplicationFactory(f.f.source,tx,clock);
    }
    SharedWriteExecutor.ActionContext action(String key){return new SharedWriteExecutor.ActionContext(key,base.input.rootId(),base.input.inputHash());}
    ResultApplicationService.Result apply(){var app=factory.forAttempt(identity);String raw=ResultApplicationService.providerEnvelope(app.input(),JdbcFrozenInputs.canonical(base.payload));return app.service().applyAutomatic(app.input(),raw,action("fictional-factory-value"));}
    void empty(){base.empty();assertNull(new JdbcResultState(f.f.source).root(identity.rootId()).resultHash());assertEquals("occupied",new JdbcWorkerState(f.f.source).slot().state());assertEquals(1,new JdbcJobRecords(f.f.source).byId(identity.rootId()).callCount());assertEquals(1,sends);}
    @Test void configuredFactoryAppliesTheOriginalPermitAndReplaysWithoutAnotherSend(){
        var result=apply();assertEquals(ResultApplicationService.Status.APPLIED,result.status());assertEquals("free",new JdbcWorkerState(f.f.source).slot().state());assertEquals("succeeded",new JdbcJobRecords(f.f.source).byId(identity.rootId()).state());
        var value=CanonicalJson.parse(f.f.jdbc.queryForObject("SELECT value_json FROM source_run_items WHERE id=?",String.class,base.base.item));assertEquals("agent",value.get("category").asText());assertEquals(base.base.entity,value.get("entity_id").asText());assertEquals(base.base.revision+2,f.f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id=?",Long.class,base.base.item));
        int count=f.f.count("1=1");factory=new ResultApplicationFactory(f.f.source,tx,clock);assertEquals(result,apply());assertEquals(count,f.f.count("1=1"));assertEquals(1,sends);
    }
    @Test void badOriginalObservationAndWrongCurrentEntityRollbackAllResultApplicationWrites(){
        String observed=f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='source_stage_availability'",String.class);f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,this::apply);empty();
        f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(observed))));f.f.jdbc.update("UPDATE source_run_items SET entity_id='fictional-other' WHERE id=?",base.base.item);assertThrows(RuntimeException.class,this::apply);empty();
    }
    @Test void resultReceiptFailureRollsBackRetentionMemberFactsAndSlotRelease(){
        f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_factory_result_failure CHECK(target_type IS NULL OR target_type<>'result_application')");assertThrows(RuntimeException.class,this::apply);empty();
        f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_factory_result_failure":"ALTER TABLE jobs DROP CHECK fictional_factory_result_failure");assertEquals(ResultApplicationService.Status.APPLIED,apply().status());assertEquals(1,sends);
    }
    @Test void aCommittedResultWithLostAckRecoversOnePermanentApplication(){
        tx.ackLost=true;var result=apply();assertEquals(ResultApplicationService.Status.APPLIED,result.status());assertEquals("free",new JdbcWorkerState(f.f.source).slot().state());assertEquals(base.base.revision+2,f.f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id=?",Long.class,base.base.item));
        factory=new ResultApplicationFactory(f.f.source,tx,clock);assertEquals(result,apply());assertEquals(1,f.f.count("target_type='result_application'"));assertEquals(1,sends);
    }
}
