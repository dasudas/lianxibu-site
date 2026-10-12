package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class OwnerJobResolveExternalServiceTest extends OwnerExternalResolutionMaterialTest {
    OwnerJobResolveExternalService consumer;
    @Override @BeforeEach void setup(){super.setup();consumer=new OwnerJobResolveExternalService(f.source,f.tx,f.writes,f.clock);}
    void refused(JobRefusal code,org.junit.jupiter.api.function.Executable work){assertEquals(code,assertThrows(JobRejected.class,work).refusal());}
    @Test void ActualOwnerConsumerRetainsNativeCompleteResultAndOriginalResponseReplaysAfterRestart(){
        var c=command("succeeded");var slot=new JdbcWorkerState(f.source).slot();var result=consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,c,"fictional-owner-confirm");assertTrue(result.rootResultRetained());assertEquals(c.expectedRevision()+1,result.revision());assertEquals("EXTERNAL_RESULT_CONFIRMED",f.jdbc.queryForObject("SELECT application_state FROM jobs WHERE id=?",String.class,ResultJdbcStoreTest.ROOT));assertEquals(0,f.count("contents"));assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(1,f.sends);
        consumer=new OwnerJobResolveExternalService(f.source,f.tx,f.writes,f.clock);long rows=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);assertEquals(result,consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,c,"fictional-owner-confirm"));assertEquals(rows,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class));
    }
    @Test void UnknownAndFailedActualConsumerNeedNoInventedResultOrTokenCounts(){
        var unknown=consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,command("unknown"),"fictional-owner-unknown");assertFalse(unknown.rootResultRetained());var failed=consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,command("failed"),"fictional-owner-failed");assertEquals(unknown.revision()+1,failed.revision());assertEquals("failed",f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,ResultJdbcStoreTest.ROOT));assertNull(f.jdbc.queryForObject("SELECT usage_json FROM jobs WHERE id=?",String.class,f.identity.attemptId()));
    }
    @Test void CurrentManagementCasAndPhysicalCostFailuresRemainClosedBusinessRefusals(){
        var c=command("succeeded");f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",ResultJdbcStoreTest.ROOT);refused(JobRefusal.REVISION_CONFLICT,()->consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,c,"fictional-stale-confirm"));var now=command("succeeded");var negative=new JobAction(now.expectedRevision(),now.action(),now.reason(),now.attemptId(),null,null,now.externalState(),now.evidenceRefs(),now.result(),new Usage(null,null,"-1"));refused(JobRefusal.INVALID_EVIDENCE,()->consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,negative,"fictional-invalid-confirm"));assertNull(new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash());
    }
    @Test void NewOwnerWithSameRequestKeyConflictsAfterValidatingOldOperatorEvidence(){
        var c=command("succeeded");consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,c,"fictional-operator-conflict");refused(JobRefusal.IDENTITY_CONFLICT,()->consumer.resolve(new Actor("fictional-second-owner","owner"),ResultJdbcStoreTest.ROOT,c,"fictional-operator-conflict"));f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='review' AND part_no=1",new byte[32]);assertThrows(IllegalStateException.class,()->consumer.resolve(new Actor("fictional-second-owner","owner"),ResultJdbcStoreTest.ROOT,c,"fictional-operator-conflict"));
    }
    @Test void CurrentInputCorruptionOrInvalidClientEnvelopeCannotPersistAResolution(){
        var c=command("succeeded");var bad=new ValidatedResult("1",c.result().inputHash(),c.result().payload(),"0".repeat(64));var badCommand=new JobAction(c.expectedRevision(),c.action(),c.reason(),c.attemptId(),null,null,c.externalState(),c.evidenceRefs(),bad,null);assertThrows(IllegalArgumentException.class,()->consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,badCommand,"fictional-bad-envelope"));f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='input' AND part_no=0",new byte[32]);assertThrows(IllegalStateException.class,()->consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,c,"fictional-bad-original"));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='job_external_resolution'",Integer.class));
    }
    @Test void OwnerAuthorizationAndActionValidationHappenBeforeAnyOriginalLookup(){
        f.jdbc.execute("DROP TABLE jobs");assertEquals(IdentityService.Refusal.UNAUTHENTICATED,assertThrows(IdentityService.Rejected.class,()->consumer.resolve(null,null,null,null)).refusal());assertEquals(IdentityService.Refusal.FORBIDDEN,assertThrows(IdentityService.Rejected.class,()->consumer.resolve(new Actor("fictional-member","member"),null,null,null)).refusal());assertThrows(IllegalArgumentException.class,()->consumer.resolve(OWNER,"fictional-root",null,"fictional-invalid"));
    }
    @Test void MissingOriginalRootAndAttemptHaveNotFoundBusinessCategory(){var c=command("unknown");refused(JobRefusal.NOT_FOUND,()->consumer.resolve(OWNER,"fictional-absent-root",c,"fictional-missing-original"));var missing=new JobAction(c.expectedRevision(),c.action(),c.reason(),"fictional-absent-attempt",null,null,c.externalState(),c.evidenceRefs(),null,null);refused(JobRefusal.NOT_FOUND,()->consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,missing,"fictional-missing-attempt"));}
    @Test void ServiceRequiresOwnershipOfOuterTransaction(){var c=command("unknown");var tx=new TransactionTemplate(f.tx);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.execute(s->{assertThrows(IllegalStateException.class,()->consumer.resolve(OWNER,ResultJdbcStoreTest.ROOT,c,"fictional-nested-owner"));return true;});assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='job_external_resolution'",Integer.class));}
}
