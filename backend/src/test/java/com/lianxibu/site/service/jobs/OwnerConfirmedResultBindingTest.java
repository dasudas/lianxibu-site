package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.JobsService.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Two native original claims and sends with the same INPUT and independently audited results. */
class OwnerConfirmedResultBindingTest {
    ResultJdbcStoreTest f;JdbcOwnerExternalAttemptReads original;
    static final com.lianxibu.site.service.identity.IdentityService.Actor OWNER=new com.lianxibu.site.service.identity.IdentityService.Actor("fictional-owner","owner");
    ResultJdbcStoreTest fixture(){var value=new ManualResultProofFixture();value.initialize(true);return value;}
    JdbcOwnerExternalAttemptReads.Frame read(){var tx=new TransactionTemplate(f.tx);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx.execute(s->original.read(ResultJdbcStoreTest.ROOT,f.identity.attemptId()));}
    JobAction command(String state){var raw=CanonicalJson.parse(f.raw());return new JobAction(read().root().revision(),"resolve_external","Fictional original verification",f.identity.attemptId(),null,null,state,java.util.List.of("fictional-evidence"),new ValidatedResult("1",f.identity.inputHash(),raw.get("payload"),raw.get("result_hash").textValue()),null);}

    String oldAttempt;OwnerJobResolveExternalService resolution;
    @BeforeEach void setup(){
        f=fixture();f.jdbc.execute("ALTER TABLE jobs ADD COLUMN error_code VARCHAR(64)");new TransportUnknownService(f.writes,new JdbcTransportUnknown(f.source),f.clock,"fictional-epoch").release(f.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,f.action("fictional-first-unknown"));original=new JdbcOwnerExternalAttemptReads(f.source);oldAttempt=f.identity.attemptId();resolution=new OwnerJobResolveExternalService(f.source,f.tx,f.writes,f.clock);
        // This owned fixture places the next prepared task at the worker admission boundary.
        // The test does not grant recovery permission to a still unknown attempt in the product.
        f.writes.execute(f.action("fictional-second-boundary"),key->null,action->{var records=new JdbcJobRecords(f.source);records.prepareAttempt(new JdbcJobRecords.Attempt(JdbcFrozenInputs.stableId("attempt",ResultJdbcStoreTest.ROOT+":2"),ResultJdbcStoreTest.ROOT,2,f.identity.frozenRequestJson(),ResultJdbcStoreTest.ROOT,f.identity.inputHash()),f.clock.instant());f.jdbc.update("UPDATE jobs SET state='queued',revision=revision+1 WHERE id=?",ResultJdbcStoreTest.ROOT);return true;});
        var second=f.worker.claim(f.action("fictional-second-claim"));assertEquals(WorkerAuthorizationService.State.CLAIMED,second.state());f.permit=second.permit();f.identity=f.permit.identity();assertEquals(WorkerAuthorizationService.State.SENT,f.worker.dispatch(f.permit,request->{f.sends++;return "Fictional response";}).state());new TransportUnknownService(f.writes,new JdbcTransportUnknown(f.source),f.clock,"fictional-epoch").release(f.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,f.action("fictional-second-unknown"));
    }
    JobAction forAttempt(String attempt){var c=command("succeeded");return new JobAction(c.expectedRevision(),c.action(),c.reason(),attempt,null,null,c.externalState(),c.evidenceRefs(),c.result(),null);}
    void retainBoth(){assertFalse(resolution.resolve(OWNER,ResultJdbcStoreTest.ROOT,forAttempt(oldAttempt),"fictional-old-audit").rootResultRetained());assertTrue(resolution.resolve(OWNER,ResultJdbcStoreTest.ROOT,command("succeeded"),"fictional-current-confirm").rootResultRetained());assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt' AND result_hash IS NOT NULL",Integer.class));}
    JdbcOwnerRetainedResultReads.Frame retained(){var tx=new TransactionTemplate(f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->new JdbcOwnerRetainedResultReads(f.source).read(ResultJdbcStoreTest.ROOT));}
    @Test void SameDigestOldAuditDoesNotMakeLatestCurrentResultAmbiguous(){retainBoth();var slot=new JdbcWorkerState(f.source).slot();var frame=retained();assertEquals(f.identity.attemptId(),frame.identity().attemptId());assertNotEquals(oldAttempt,frame.identity().attemptId());assertEquals(2,frame.root().callCount());assertEquals("EXTERNAL_RESULT_CONFIRMED",frame.root().applicationState());assertEquals(CanonicalJson.parse(f.raw()),CanonicalJson.parse(frame.envelope()));assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(2,f.sends);assertEquals(0,f.count("contents"));}
    @Test void ActualManualWriterReadsSameLatestOriginalAndLeavesBothAuditsUntouched(){retainBoth();var expected=retained();long count=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);var result=f.writes.execute(f.action("fictional-read-application"),key->null,a->new JdbcOwnerRetainedResultReads(f.source).readForApplication(ResultJdbcStoreTest.ROOT));assertEquals(expected.identity(),result.identity());assertEquals(expected.envelope(),result.envelope());assertEquals(count,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class));}
    @Test void MissingCurrentSendAuthorizationCannotFallBackToOlderValidSameDigest(){retainBoth();f.jdbc.update("UPDATE jobs SET send_authorized_at=NULL WHERE id=?",f.identity.attemptId());assertThrows(IllegalStateException.class,this::retained);assertEquals("succeeded",f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,oldAttempt));}
    @Test void CorruptCurrentOriginalClaimCannotUseOlderClaimAsAuthority(){retainBoth();f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim' AND target_key_json LIKE ?",new byte[32],"%"+f.identity.attemptId()+"%");assertThrows(IllegalStateException.class,this::retained);}
}
