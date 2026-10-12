package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

class SourceValueIntentReceiptTest {
    SourceDedupStageProofServiceTest f;SourceValueIntentReceiptService service;SourceDedupStageResult proof;Instant observed,created;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){
        f=new SourceDedupStageProofServiceTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueIntentReceiptTest.this.database();}};f.setup();f.complete(0);f.complete(1);
        f.f.f.jdbc.execute(f.f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs ALTER COLUMN acted_at TIMESTAMP(6)":"ALTER TABLE jobs MODIFY acted_at TIMESTAMP(6) NULL");
        proof=f.read();var ledger=new JdbcSourceStageAvailability(f.f.f.source);observed=new SourceDedupStageAvailabilityService(f.service,ledger,f.f.f.writes,new DataSourceTransactionManager(f.f.f.source),f.f.f.clock).capture(f.ref);created=observed.plusSeconds(2);
        service=new SourceValueIntentReceiptService(new JdbcSourceValueIntents(f.f.f.source),ledger);
    }
    SourceValueIntentReceiptService.Handoff value(long generation){return SourceValueIntentReceiptService.Handoff.create(proof,f.f.original,observed,generation,created);}
    SourceValueIntentReceiptService.Handoff read(){return f.f.readonly(()->service.read(f.ref.ownerId()));}
    int handoffs(){return f.f.f.count("target_type='source_value_intent'");}
    @Test void oneNativeMetadataRelationshipSurvivesRestartWithoutNewRootsOrChangingP1(){
        var expected=value(1);int calls=f.f.f.count("record_kind IN ('logical_task','ai_attempt')");assertNull(read());
        assertEquals(NewsValueSourcePlanBuilder.logicalKey(FrozenSourceDedupDependency.create(proof,observed,observed)),expected.p2Key());
        assertThrows(RuntimeException.class,()->f.f.tx(()->service.reserve(value(99))));assertEquals(0,handoffs());assertEquals(0,f.f.f.count("record_kind='generation_intent' AND task_type='news_value'"));
        assertEquals(1,f.f.tx(()->service.nextGeneration(expected.p2Key(),expected.day())));assertEquals(expected.json(),f.f.tx(()->service.reserve(expected)).json());
        assertEquals(expected.json(),read().json());assertEquals(expected.json(),f.f.tx(()->service.reserve(expected)).json());assertEquals(1,handoffs());assertEquals(calls,f.f.f.count("record_kind IN ('logical_task','ai_attempt')"));
        assertEquals("waiting_input",f.f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId()));
        assertThrows(RuntimeException.class,()->service.reserve(expected));
    }
    @Test void aBarePriorIntentConsumesItsGenerationAndIsNeverOverwritten(){
        var first=value(1);f.f.tx(()->new JdbcJobRecords(f.f.f.source).ensureIntent(new JdbcJobRecords.Intent(first.p2Owner(),first.p2Key(),1,"news_value",first.day()),created));
        assertThrows(RuntimeException.class,()->f.f.tx(()->service.reserve(first)));assertEquals(0,handoffs());assertEquals(2,f.f.tx(()->service.nextGeneration(first.p2Key(),first.day())));
        var next=value(2);assertEquals(next.json(),f.f.tx(()->service.reserve(next)).json());assertEquals(2,f.f.f.count("record_kind='generation_intent' AND task_type='news_value'"));assertEquals(1,handoffs());
    }
    @Test void failedReceiptRollsBackTheNewIntentAndCommitAckLossRecoversTheSameRelationship(){
        var expected=value(1);f.f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_value_intent_failure CHECK(target_type IS NULL OR target_type<>'source_value_intent')");assertThrows(RuntimeException.class,()->f.f.tx(()->service.reserve(expected)));assertEquals(0,handoffs());assertEquals(0,f.f.f.count("record_kind='generation_intent' AND task_type='news_value'"));
        f.f.f.jdbc.execute(f.f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_value_intent_failure":"ALTER TABLE jobs DROP CHECK fictional_value_intent_failure");
        var fault=new DataSourceTransactionManager(f.f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional handoff ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.f.source),fault,()->25,n->{});
        var result=writes.execute(new SharedWriteExecutor.ActionContext("fictional-value-handoff-ack",f.ref.ownerId(),null),key->service.read(f.ref.ownerId()),action->service.reserve(expected));assertEquals(expected.json(),result.json());assertEquals(expected.json(),read().json());assertEquals(1,handoffs());assertEquals(1,f.f.f.count("record_kind='generation_intent' AND task_type='news_value'"));
    }
    @Test void rehashedMetadataAndChangedNativeOwnersOrOriginalArchivesAreRejected(){
        var expected=value(1);f.f.tx(()->service.reserve(expected));String id=SourceValueIntentReceiptService.receiptId(f.ref.ownerId());
        var body=(ObjectNode)CanonicalJson.parse(expected.json());body.put("p2_generation",2);String raw=JdbcFrozenInputs.canonical(body);f.f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);assertThrows(RuntimeException.class,this::read);
        f.f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",expected.json(),JdbcGenerationLineage.hash(expected.json()),id);assertNotNull(read());
        f.f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-other' WHERE id=?",id);assertThrows(RuntimeException.class,this::read);f.f.f.jdbc.update("UPDATE jobs SET operator_id=? WHERE id=?",JdbcSourceValueIntents.ACTOR,id);
        f.f.f.jdbc.update("UPDATE jobs SET logical_key_hash=? WHERE id=?",new byte[32],expected.p2Owner());assertThrows(RuntimeException.class,this::read);f.f.f.jdbc.update("UPDATE jobs SET logical_key_hash=? WHERE id=?",JdbcGenerationLineage.hash(expected.p2Key()),expected.p2Owner());
        f.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(f.ref.ownerId()));assertThrows(RuntimeException.class,this::read);
    }
    @Test void cancelledOriginalIntentOrMissingPermanentObservationCannotCreateAHandoff(){
        var expected=value(1);f.f.f.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",f.ref.ownerId());assertThrows(RuntimeException.class,()->f.f.tx(()->service.reserve(expected)));assertEquals(0,handoffs());f.f.f.jdbc.update("UPDATE jobs SET state='waiting_input' WHERE id=?",f.ref.ownerId());
        f.f.f.jdbc.update("DELETE FROM jobs WHERE target_type='source_stage_availability'");assertThrows(RuntimeException.class,()->f.f.tx(()->service.reserve(expected)));assertEquals(0,handoffs());assertEquals(0,f.f.f.count("record_kind='generation_intent' AND task_type='news_value'"));
    }
}
