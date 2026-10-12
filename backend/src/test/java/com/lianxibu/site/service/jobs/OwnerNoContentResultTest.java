package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import org.junit.jupiter.api.Test;

class OwnerNoContentResultTest extends NonContentManualFactoryTest {
    OwnerJobApplyResultService ownerService(){var graph=new SourceAnalysisProofGraph(f.source,f.tx,f.clock,()->false);var factory=new ResultApplicationFactory(f.source,f.tx,f.clock,graph.dependencies(),graph.dedupPermits());return new OwnerJobApplyResultService(new JdbcOwnerRetainedResultReads(f.source),f.tx,factory::forManual);}
    @Test void ActualOwnerApplicationCommitsNullContentReceiptAndReplaysOriginalResult(){long before=revision();var service=ownerService();var slot=new JdbcWorkerState(f.source).slot();var result=service.apply(owner,ResultJdbcStoreTest.ROOT,before,(Long)null,hash,"Fictional separate owner application","fictional-owner-no-content");assertEquals(before+1,result.revision());assertNull(result.contentId());assertNull(result.contentRevision());assertEquals(slot,new JdbcWorkerState(f.source).slot());invalidate();assertEquals(result,service.apply(owner,ResultJdbcStoreTest.ROOT,before,(Long)null,hash,"Fictional separate owner application","fictional-owner-no-content"));assertEquals(0,f.count("contents"));assertEquals(1,f.sends);}
    @Test void NonContentCannotUseInventedZeroOrNewRequestToBypassFrozenReferences(){long before=revision();var service=ownerService();assertThrows(IllegalArgumentException.class,()->service.apply(owner,ResultJdbcStoreTest.ROOT,before,0L,hash,"Fictional wrong shape","fictional-owner-zero"));assertEquals(before,revision());invalidate();assertEquals(JobsService.JobRefusal.INVALID_EVIDENCE,assertThrows(JobsService.JobRejected.class,()->service.apply(owner,ResultJdbcStoreTest.ROOT,before,(Long)null,hash,"Fictional invalid reference","fictional-owner-invalid")).refusal());assertEquals(before,revision());}
    @Test void OwnerGatePrecedesOriginalSqlForNullableApplication(){var service=ownerService();f.jdbc.execute("DROP TABLE jobs");assertThrows(IdentityService.Rejected.class,()->service.apply(null,ResultJdbcStoreTest.ROOT,0,(Long)null,hash,"Fictional denied","fictional-owner-denied"));assertThrows(IdentityService.Rejected.class,()->service.apply(new IdentityService.Actor("fictional-member","member"),ResultJdbcStoreTest.ROOT,0,(Long)null,hash,"Fictional denied","fictional-owner-denied"));}
}
