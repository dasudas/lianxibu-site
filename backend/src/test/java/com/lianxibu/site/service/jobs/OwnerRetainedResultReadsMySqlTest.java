package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Only the owned loopback B44 schema; original worker sender remains explicitly fictional. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerRetainedResultReadsMySqlTest {
    ResultJdbcStoreTest f;JdbcOwnerRetainedResultReads store;TransactionTemplate reads;
    @BeforeEach void setup(){initialize(true);}
    void initialize(boolean retained){
        f=new ResultJdbcMySqlTest();f.fixture();
        f.jdbc.execute("ALTER TABLE jobs ADD COLUMN report_mode VARCHAR(32)");
        if(retained){f.existingContent();assertEquals(ResultApplicationService.Status.CONTENT_REVISION_CONFLICT,f.apply().status());}
        store=new JdbcOwnerRetainedResultReads(f.source);reads=new TransactionTemplate(f.tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    JdbcOwnerRetainedResultReads.Frame read(){return reads.execute(s->store.read(ResultJdbcStoreTest.ROOT));}
    @Test void originalNativeClaimInputAndResultAreReadWithoutChangingAnyOriginalOrNewSlot(){
        var before=new JdbcJobRecords(f.source).byId(f.identity.attemptId());
        f.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-new-attempt',holder='fictional-new-worker',slot_revision=slot_revision+1,fencing_token=fencing_token+1");
        var slot=new JdbcWorkerState(f.source).slot();String body=f.jdbc.queryForObject("SELECT body_json FROM contents WHERE id='fictional-report'",String.class);
        var frame=read();assertEquals(ResultJdbcStoreTest.ROOT,frame.root().id());assertEquals("result_conflict",frame.root().state());assertEquals(1,frame.generation());assertEquals(1,frame.latestGeneration());assertEquals(f.identity.inputHash(),frame.inputHash());
        assertEquals(CanonicalJson.parse(f.raw()),CanonicalJson.parse(frame.envelope()));assertEquals(f.identity.attemptId(),frame.identity().attemptId());assertEquals(f.identity.leaseUntil(),frame.identity().leaseUntil());assertEquals(1,frame.root().callCount());
        assertEquals(new JdbcFrozenInputs(f.source).read(ResultJdbcStoreTest.ROOT,f.identity.inputHash()),frame.inputJson());assertFalse(frame.toString().contains("Fictional"));
        assertEquals(before,new JdbcJobRecords(f.source).byId(f.identity.attemptId()));assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(body,f.jdbc.queryForObject("SELECT body_json FROM contents WHERE id='fictional-report'",String.class));assertEquals(1,f.sends);
    }
    @Test void absentResultDiffersFromMissingWrongRoleAndInternalDirectoryOwner(){
        initialize(false);var frame=read();assertNotNull(frame);assertNull(frame.resultHash());assertNull(frame.envelope());assertNull(frame.identity());
        assertNull(reads.execute(s->store.read("fictional-absent")));assertNull(reads.execute(s->store.read(f.identity.attemptId())));
        String intent=f.jdbc.queryForObject("SELECT intent_id FROM jobs WHERE id=?",String.class,ResultJdbcStoreTest.ROOT);assertNull(reads.execute(s->store.read(intent)));
        f.jdbc.update("UPDATE jobs SET task_type='source_day_roster' WHERE id=?",ResultJdbcStoreTest.ROOT);assertNull(read());
    }
    @Test void originalInputPartAndRetainedPartHashesAreMandatory(){
        f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='input' AND part_no=0",new byte[32],ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result'",new byte[32]);assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET stamp_id='fictional-wrong-stamp' WHERE purpose='result'");assertThrows(RuntimeException.class,this::read);
    }
    @Test void originalNativeClaimRequestAndSendAuthorizationDamageCannotBecomeAResultProof(){
        f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim'",new byte[32]);assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET request_json=REPLACE(request_json,'Fictional fixture','Fictional altered') WHERE id=?",f.identity.attemptId());assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET send_authorized_at=NULL WHERE id=?",f.identity.attemptId());assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET snapshot_owner_id='fictional-wrong-owner' WHERE id=?",f.identity.attemptId());assertThrows(RuntimeException.class,this::read);
    }
    @Test void oversizedManifestRequestRetainedPartAndUnexpectedAttemptPayloadRefuse(){
        f.jdbc.update("UPDATE jobs SET result_json=? WHERE id=?","{\"fictional\":\""+"X".repeat(20000)+"\"}",ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET request_json=? WHERE id=?","{\"fictional\":\""+"X".repeat(1048577)+"\"}",f.identity.attemptId());assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET payload_json=? WHERE purpose='result'","{\"fictional\":\""+"X".repeat(262145)+"\"}");assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET payload_json='{}' WHERE id=?",f.identity.attemptId());assertThrows(RuntimeException.class,this::read);
    }
    @Test void originalAndLatestGenerationAndAppliedDiscardedStateRemainDistinct(){
        f.writes.execute(f.action("fictional-next-read-generation"),key->null,c->{new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent("fictional-next-intent",f.generation.command(false).logicalKey(),2,"daily_report",java.time.LocalDate.of(2026,1,1)),f.clock.instant());return true;});
        var frame=read();assertEquals(1,frame.generation());assertEquals(2,frame.latestGeneration());assertEquals(f.identity.inputHash(),frame.inputHash());
        f.jdbc.update("UPDATE jobs SET state='discarded',application_state='DISCARDED',revision=revision+1 WHERE id=?",ResultJdbcStoreTest.ROOT);assertEquals("DISCARDED",read().root().applicationState());
        f.jdbc.update("UPDATE jobs SET state='succeeded',application_state='APPLIED',applied_at=? WHERE id=?",java.sql.Timestamp.from(f.clock.instant()),ResultJdbcStoreTest.ROOT);assertEquals(f.clock.instant(),read().appliedAt());
    }
    @Test void originalLogicalKeyHashAndLatestNativeIntentMustActuallyMatch(){
        f.jdbc.update("UPDATE jobs SET logical_key='fictional-damaged-key' WHERE id=?",ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET logical_key='fictional-damaged-intent' WHERE record_kind='generation_intent'");assertThrows(RuntimeException.class,this::read);
        initialize(true);f.jdbc.update("UPDATE jobs SET logical_key=? WHERE id=?","X".repeat(4097),ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read);
    }
    @Test void requiresReadonlyRepeatableReadAndKeepsOneSnapshotAcrossIndependentWriter()throws Exception{
        assertThrows(RuntimeException.class,()->store.read(ResultJdbcStoreTest.ROOT));var writer=new TransactionTemplate(f.tx);assertThrows(RuntimeException.class,()->writer.execute(s->store.read(ResultJdbcStoreTest.ROOT)));
        var wrong=new TransactionTemplate(f.tx);wrong.setReadOnly(true);wrong.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->wrong.execute(s->store.read(ResultJdbcStoreTest.ROOT)));
        long revision=read().root().revision();try(var pool=Executors.newSingleThreadExecutor()){
            reads.execute(s->{var first=store.read(ResultJdbcStoreTest.ROOT);try{pool.submit(()->f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",ResultJdbcStoreTest.ROOT)).get(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception failure){throw new IllegalStateException(failure);}var second=store.read(ResultJdbcStoreTest.ROOT);assertEquals(revision,first.root().revision());assertEquals(revision,second.root().revision());assertEquals(first.envelope(),second.envelope());return true;});
        }assertEquals(revision+1,read().root().revision());assertEquals(1,f.sends);
    }
}
