package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import org.junit.jupiter.api.Test;

/** Native original response intake survives failure; no new business qualification or send is granted. */
class CatalogVersionFailureRetentionTest extends CatalogVersionCrossSourceFixture {
    void secondSent(){functional("agent",false,false,true);prepareSecondOriginalSource();assertEquals(Status.APPLIED,apply().status());sendSecond();}
    void retained(){
        var root=new JdbcResultState(f.source).root(application.input().rootId());assertEquals(ResultApplicationService.validate(application.input(),raw).resultHash(),root.resultHash());assertNull(root.appliedAt());
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE owner_job_id=? AND purpose='result'",Integer.class,application.input().rootId()));assertNull(f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id=?",String.class,SECOND_ITEM));
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Long.class));assertEquals(1,new JdbcJobRecords(f.source).byId(application.input().rootId()).callCount());assertEquals("occupied",new JdbcWorkerState(f.source).slot().state());assertEquals(3,f.sends);
    }
    @Test void NativePreflightFailureRetainsExactOriginalResponseAndSameSentAttemptCanResumeAfterRepair(){
        secondSent();String config=f.jdbc.queryForObject("SELECT config_snapshot_json FROM source_runs WHERE id=?",String.class,SECOND_RUN);f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}' WHERE id=?",SECOND_RUN);
        assertThrows(RuntimeException.class,this::apply);retained();int jobs=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertThrows(RuntimeException.class,this::apply);retained();assertEquals(jobs,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        f.jdbc.update("UPDATE source_runs SET config_snapshot_json=? WHERE id=?",config,SECOND_RUN);assertEquals(Status.APPLIED,apply().status());assertEquals("free",new JdbcWorkerState(f.source).slot().state());assertEquals(3,f.sends);
    }
    @Test void ClosedPendingAnalysisAndRetentionCommitAckUnknownPreserveOriginalFailureWithoutAllocatingAgain(){
        secondSent();String pending=JdbcFrozenInputs.stableId("intent","fictional-analysis-key:2");f.jdbc.update("UPDATE jobs SET state='queued' WHERE id=?",pending);
        assertThrows(RuntimeException.class,this::apply);retained();f.jdbc.update("UPDATE jobs SET state='waiting_input' WHERE id=?",pending);assertEquals(Status.APPLIED,apply().status());assertEquals(3,f.sends);
        secondSent();f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}' WHERE id=?",SECOND_RUN);f.tx.ackLost=true;
        var failure=assertThrows(RuntimeException.class,this::apply);assertEquals(0,failure.getSuppressed().length);retained();
    }
    @Test void InvalidResultOrCorruptedOriginalInputCannotBeRetainedAsAValidSentResponse(){
        secondSent();String good=raw;raw="{}";assertThrows(ResultApplicationService.Rejected.class,this::apply);assertNull(new JdbcResultState(f.source).root(application.input().rootId()).resultHash());raw=good;
        f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='input'",new byte[32],application.input().rootId());assertThrows(RuntimeException.class,this::apply);assertNull(new JdbcResultState(f.source).root(application.input().rootId()).resultHash());assertEquals(3,f.sends);
    }
}
