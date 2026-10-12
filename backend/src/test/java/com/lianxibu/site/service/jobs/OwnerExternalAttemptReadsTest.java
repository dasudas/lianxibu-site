package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual original INPUT, worker claim/send and transport-unknown protocol; no result is retained. */
class OwnerExternalAttemptReadsTest {
    ResultJdbcStoreTest f;JdbcOwnerExternalAttemptReads original;
    ResultJdbcStoreTest fixture(){var value=new ManualResultProofFixture();value.initialize(true);return value;}
    @BeforeEach void setup(){f=fixture();f.jdbc.execute("ALTER TABLE jobs ADD COLUMN error_code VARCHAR(64)");new TransportUnknownService(f.writes,new JdbcTransportUnknown(f.source),f.clock,"fictional-epoch").release(f.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,f.action("fictional-external-unknown"));original=new JdbcOwnerExternalAttemptReads(f.source);}
    JdbcOwnerExternalAttemptReads.Frame read(){var tx=new TransactionTemplate(f.tx);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx.execute(s->original.read(ResultJdbcStoreTest.ROOT,f.identity.attemptId()));}
    long rows(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);}
    @Test void OriginalUnknownAttemptNeedsNoRetainedResultAndPreservesNativeClaims(){
        long before=rows();var slot=new JdbcWorkerState(f.source).slot();var proof=read();assertNotNull(proof);assertEquals("unknown_external",proof.attemptState());assertEquals(f.identity.attemptId(),proof.identity().attemptId());assertEquals(f.identity.inputHash(),proof.identity().inputHash());assertEquals(f.identity.frozenRequestJson(),proof.identity().frozenRequestJson());assertTrue(proof.originalRootRevision()<proof.root().revision());assertEquals(1,proof.attemptNo());assertEquals(1,proof.generation());assertEquals(f.identity.inputHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,CanonicalJson.parse(proof.inputJson())));assertNull(new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash());assertEquals(before,rows());assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals("OwnerOriginalExternalAttemptFrame[redacted]",proof.toString());
    }
    @Test void ANewCurrentHolderOrLaterCounterCannotBecomeOriginalAttemptAuthority(){
        var before=read();f.jdbc.update("UPDATE jobs SET call_count=2,revision=revision+3 WHERE id=?",ResultJdbcStoreTest.ROOT);f.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-new-attempt',holder='fictional-new-holder',slot_revision=91,fencing_token=91");var slot=new JdbcWorkerState(f.source).slot();var after=read();assertEquals(before.identity(),after.identity());assertEquals(before.inputJson(),after.inputJson());assertEquals(1,after.attemptNo());assertEquals(2,after.root().callCount());assertEquals(slot,new JdbcWorkerState(f.source).slot());
        f.jdbc.execute("DROP TABLE worker_lock");assertEquals(before.identity(),read().identity());assertEquals(1,f.sends);
    }
    @Test void WrongRootAttemptAndRoleCannotProduceAFrame(){
        var tx=new TransactionTemplate(f.tx);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.execute(s->{assertNull(original.read("fictional-absent-root",f.identity.attemptId()));assertNull(original.read(ResultJdbcStoreTest.ROOT,"fictional-absent-attempt"));return true;});
        f.jdbc.update("UPDATE jobs SET root_job_id='fictional-other-root' WHERE id=?",f.identity.attemptId());assertNull(read());
    }
    @Test void OriginalRequestHashOwnerAuthorizationAndCapacityAreRequired(){
        String request=f.identity.frozenRequestJson();f.jdbc.update("UPDATE jobs SET snapshot_owner_id='fictional-other-owner' WHERE id=?",f.identity.attemptId());assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE jobs SET snapshot_owner_id=? WHERE id=?",ResultJdbcStoreTest.ROOT,f.identity.attemptId());
        f.jdbc.update("UPDATE jobs SET send_authorized_at=NULL WHERE id=?",f.identity.attemptId());assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE jobs SET send_authorized_at=created_at WHERE id=?",f.identity.attemptId());
        f.jdbc.update("UPDATE jobs SET request_json=? WHERE id=?","{\"fictional\":\""+"x".repeat(1048576)+"\"}",f.identity.attemptId());assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE jobs SET request_json=? WHERE id=?",request,f.identity.attemptId());
        f.jdbc.update("UPDATE jobs SET input_hash=? WHERE id=?",new byte[32],f.identity.attemptId());assertThrows(RuntimeException.class,this::read);
    }
    @Test void OriginalClaimAndInputPartDigestsCannotBeReconstructed(){
        byte[] hash=f.jdbc.queryForObject("SELECT action_payload_hash FROM jobs WHERE target_type='worker_claim'",byte[].class);f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim'",new byte[32]);assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim'",hash);assertNotNull(read());
        f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE record_kind='snapshot_part' AND purpose='input'",new byte[32]);assertThrows(RuntimeException.class,this::read);
    }
    @Test void OriginalNaturalKeyAndCurrentCounterMustRemainNativeAndBounded(){
        f.jdbc.update("UPDATE jobs SET logical_key='fictional-substituted-key' WHERE id=?",ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read);
        setup();f.jdbc.update("UPDATE jobs SET call_count=0 WHERE id=?",ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read);
    }
    @Test void ActualWriterIsolationIsRequiredAndDoesNotWeakenOriginalReadOnlyPorts(){
        assertThrows(RuntimeException.class,()->original.read(ResultJdbcStoreTest.ROOT,f.identity.attemptId()));
        var tx=new TransactionTemplate(f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.execute(s->{assertThrows(RuntimeException.class,()->original.read(ResultJdbcStoreTest.ROOT,f.identity.attemptId()));return true;});
        var wrong=new TransactionTemplate(f.tx);wrong.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);wrong.execute(s->{assertThrows(RuntimeException.class,()->original.read(ResultJdbcStoreTest.ROOT,f.identity.attemptId()));return true;});
        var writer=new TransactionTemplate(f.tx);writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writer.execute(s->{assertNotNull(original.read(ResultJdbcStoreTest.ROOT,f.identity.attemptId()));assertThrows(RuntimeException.class,()->new JdbcOwnerJobViews(f.source).job(ResultJdbcStoreTest.ROOT));return true;});
    }
}
