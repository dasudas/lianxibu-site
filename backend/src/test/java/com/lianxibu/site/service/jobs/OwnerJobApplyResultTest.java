package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import org.junit.jupiter.api.*;

class OwnerJobApplyResultTest extends ManualResultFactoryTest {
    OwnerJobApplyResultService service(){return new OwnerJobApplyResultService(new JdbcOwnerRetainedResultReads(f.source),f.tx,factory::forManual);}
    String hash(){return new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash();}
    OwnerJobApplyResultService.Applied confirm(String key,long expected,long content){return service().apply(owner,ResultJdbcStoreTest.ROOT,expected,content,hash(),"Fictional owner confirmation",key);}
    @Test void permanentOwnerConfirmationReturnsOriginalJobAndContentRevisionsAndReplays(){long before=revision();var slot=new JdbcWorkerState(f.source).slot();f.tx.ackLost=true;var receipt=confirm("fictional-owner-confirm",before,1);
        assertEquals(before+1,receipt.revision());assertEquals(2,receipt.contentRevision());assertEquals("fictional-report",receipt.contentId());assertEquals(slot,new JdbcWorkerState(f.source).slot());
        f.jdbc.update("UPDATE contents SET revision=revision+7,body_hash_v1=?",new byte[32]);f.jdbc.update("UPDATE jobs SET revision=revision+3 WHERE id=?",ResultJdbcStoreTest.ROOT);
        assertEquals(receipt,confirm("fictional-owner-confirm",before,1));assertEquals(1,f.sends);assertEquals(1,new JdbcJobRecords(f.source).byId(ResultJdbcStoreTest.ROOT).callCount());
    }
    @Test void explicitBusinessRejectionsDoNotBecomeStorageUnavailable(){long before=revision();assertEquals(JobRefusal.REVISION_CONFLICT,assertThrows(JobRejected.class,()->confirm("fictional-bad-root-cas",before-1,1)).refusal());assertEquals(JobRefusal.REVISION_CONFLICT,assertThrows(JobRejected.class,()->confirm("fictional-bad-content-cas",before,0)).refusal());
        assertEquals(JobRefusal.IDENTITY_CONFLICT,assertThrows(JobRejected.class,()->service().apply(owner,ResultJdbcStoreTest.ROOT,before,1,"0".repeat(64),"Fictional wrong confirmation","fictional-bad-hash")).refusal());
        confirm("fictional-key-bind",before,1);assertEquals(JobRefusal.IDENTITY_CONFLICT,assertThrows(JobRejected.class,()->service().apply(owner,ResultJdbcStoreTest.ROOT,before,1,hash(),"Fictional changed reason","fictional-key-bind")).refusal());
        assertEquals(JobRefusal.IDENTITY_CONFLICT,assertThrows(JobRejected.class,()->service().apply(new Actor("fictional-other-owner","owner"),ResultJdbcStoreTest.ROOT,before,1,hash(),"Fictional owner confirmation","fictional-key-bind")).refusal());assertEquals(1,f.sends);
    }
    @Test void latestGenerationCannotBeOverriddenByOldReadComparison(){long before=revision();f.writes.execute(f.action("fictional-owner-next-gen"),k->null,c->{new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent("fictional-owner-next-intent",f.generation.command(false).logicalKey(),2,"daily_report",java.time.LocalDate.of(2026,1,1)),f.clock.instant());return true;});assertEquals(JobRefusal.SUPERSEDED,assertThrows(JobRejected.class,()->confirm("fictional-owner-old-generation",before,1)).refusal());assertEquals(before,revision());}
    @Test void rootAndCurrentOwnerAreRequired(){long before=revision();assertEquals(JobRefusal.NOT_FOUND,assertThrows(JobRejected.class,()->service().apply(owner,"fictional-missing",before,1,hash(),"Fictional missing root","fictional-missing")).refusal());
        assertEquals(JobRefusal.NOT_FOUND,assertThrows(JobRejected.class,()->service().apply(owner,f.identity.attemptId(),before,1,hash(),"Fictional attempt target","fictional-attempt")).refusal());
        assertThrows(IdentityService.Rejected.class,()->service().apply(new Actor("fictional-reader","user"),ResultJdbcStoreTest.ROOT,before,1,hash(),"Fictional denied","fictional-denied"));assertEquals(before,revision());}
    @Test void legacyCurrentProofAndOriginalNativeDamageKeepStorageFailureAndNoWrites(){long before=revision();f.jdbc.update("UPDATE contents SET body_revision=NULL,content_key_json=NULL");var failure=assertThrows(RuntimeException.class,()->confirm("fictional-owner-legacy-proof",before,1));assertFalse(failure instanceof JobRejected);assertEquals(before,revision());
        f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result'",new byte[32]);failure=assertThrows(RuntimeException.class,()->confirm("fictional-owner-damaged-result",before,1));assertFalse(failure instanceof JobRejected);assertEquals(before,revision());assertEquals(1,f.sends);}
}
