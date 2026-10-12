package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerResultComparisonMySqlTest {
    ResultJdbcStoreTest f;JobResultComparisonReadService service;
    final Actor owner=new Actor("fictional-owner","owner");
    @BeforeEach void setup(){f=new OwnerResultComparisonFixture();f.fixture();f.existingContent();assertEquals(ResultApplicationService.Status.CONTENT_REVISION_CONFLICT,f.apply().status());assemble();}
    boolean valid(tools.jackson.databind.JsonNode target){
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        return "article_revision".equals(target.path("kind").asText())&&f.jdbc.query("SELECT effective_valid FROM article_revisions WHERE id=?",(r,n)->r.getBoolean(1),target.path("id").asText()).stream().anyMatch(Boolean.TRUE::equals);
    }
    void assemble(){
        var content=new ContentReadService(new JdbcContentReads(f.source),f.tx,ContentReadService.denyUnverifiedReferences());
        service=new JobResultComparisonReadService(new OwnerJobResultComparisonStore(f.source,f.tx,content,this::valid));
    }
    JobResultComparisonReadService.Comparison compare(){return (JobResultComparisonReadService.Comparison)service.read(owner,ResultJdbcStoreTest.ROOT);}
    @Test void originalFrozenReportProfileCurrentNativeBodyAndRetainedCandidateAreComparable(){
        f.jdbc.update("UPDATE contents SET revision=6");var view=compare();assertTrue(view.canApply());assertEquals(6,view.currentContentRevision());assertEquals(1,view.currentBodyRevision());assertEquals(1,view.generation());assertEquals("complete",view.coverage().state());
        assertEquals(ResultJdbcStoreTest.ROOT,view.inputSnapshotId());assertEquals("Fictional report",view.candidateBody().get("title").asText());assertEquals(1,f.sends);assertEquals(1,new JdbcJobRecords(f.source).byId(ResultJdbcStoreTest.ROOT).callCount());
    }
    @Test void laterIntentActualReferenceInvalidationDiscardedAndAppliedRemainBlocked(){
        f.writes.execute(f.action("fictional-next-comparison-generation"),key->null,c->{new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent("fictional-next-intent",f.generation.command(false).logicalKey(),2,"daily_report",java.time.LocalDate.of(2026,1,1)),f.clock.instant());return true;});
        assertTrue(compare().blockingReasons().contains("superseded"));assertFalse(compare().canApply());
        f.jdbc.update("UPDATE article_revisions SET effective_valid=false");assertTrue(compare().blockingReasons().contains("invalid_evidence"));
        f.jdbc.update("UPDATE jobs SET state='discarded',application_state='DISCARDED' WHERE id=?",ResultJdbcStoreTest.ROOT);assertTrue(compare().blockingReasons().contains("discarded"));
        f.jdbc.update("UPDATE jobs SET state='succeeded',application_state='APPLIED',applied_at=? WHERE id=?",java.sql.Timestamp.from(f.clock.instant()),ResultJdbcStoreTest.ROOT);assertTrue(compare().blockingReasons().contains("already_applied"));
    }
    @Test void legacyInputWithoutReportProfileIsKnownUnprovenRatherThanNoResult(){
        f=new ResultJdbcMySqlTest();f.fixture();f.jdbc.execute("ALTER TABLE jobs ADD COLUMN report_mode VARCHAR(32)");f.existingContent();f.apply();assemble();
        var view=(JobResultComparisonReadService.Unavailable)service.read(owner,ResultJdbcStoreTest.ROOT);assertEquals("unproven_input",view.reason());assertNotNull(f.jdbc.queryForObject("SELECT result_hash FROM jobs WHERE id=?",byte[].class,ResultJdbcStoreTest.ROOT));
    }
    @Test void nativeLegacyContentCannotUseCompatibilityManagementRevisionAsBodyProof(){
        f.jdbc.update("UPDATE contents SET revision=8,body_revision=NULL,content_key_json=NULL");var view=(JobResultComparisonReadService.Unavailable)service.read(owner,ResultJdbcStoreTest.ROOT);assertEquals("unproven_current_version",view.reason());
        assertEquals(8,new ContentReadService(new JdbcContentReads(f.source),f.tx,ContentReadService.denyUnverifiedReferences()).administrativeVersion("report","fictional-report").orElseThrow().bodyRevision());
    }
    @Test void absentResultMissingRootAndAttemptsHaveDistinctMeaningsAndOwnerIsRequired(){
        f=new OwnerResultComparisonFixture();f.fixture();assemble();assertEquals("no_retained_result",((JobResultComparisonReadService.Unavailable)service.read(owner,ResultJdbcStoreTest.ROOT)).reason());
        assertThrows(JobResultComparisonReadService.Missing.class,()->service.read(owner,"fictional-missing"));assertThrows(JobResultComparisonReadService.Missing.class,()->service.read(owner,f.identity.attemptId()));
        assertThrows(JobResultComparisonReadService.Denied.class,()->service.read(new Actor("fictional-reader","user"),ResultJdbcStoreTest.ROOT));
    }
    @Test void originalInputResultAndCurrentBodyDamageAreStorageProofFailures(){
        f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='input'",new byte[32]);assertThrows(IllegalStateException.class,this::compare);
        setup();f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result'",new byte[32]);assertThrows(IllegalStateException.class,this::compare);
        setup();f.jdbc.update("UPDATE contents SET body_hash_v1=?",new byte[32]);assertThrows(IllegalStateException.class,this::compare);
    }
    @Test void laterSlotHolderBudgetOriginalAttemptAndContentRemainUntouched(){
        f.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-new-attempt',holder='fictional-new-holder',slot_revision=slot_revision+1,fencing_token=fencing_token+1");
        var slot=new JdbcWorkerState(f.source).slot();var attempt=new JdbcJobRecords(f.source).byId(f.identity.attemptId());String body=f.jdbc.queryForObject("SELECT body_json FROM contents",String.class);
        compare();compare();assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(attempt,new JdbcJobRecords(f.source).byId(f.identity.attemptId()));assertEquals(body,f.jdbc.queryForObject("SELECT body_json FROM contents",String.class));assertEquals(1,f.sends);
    }
    @Test void oneSnapshotKeepsRootAndBodyRevisionsAcrossConcurrentWriter()throws Exception{
        long rootRevision=compare().jobRevision();try(var pool=Executors.newSingleThreadExecutor()){
            var content=new ContentReadService(new JdbcContentReads(f.source),f.tx,ContentReadService.denyUnverifiedReferences());
            var hooked=new JobResultComparisonReadService(new OwnerJobResultComparisonStore(f.source,f.tx,content,target->{
                try{pool.submit(()->{f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",ResultJdbcStoreTest.ROOT);f.jdbc.update("UPDATE contents SET revision=revision+1");}).get(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception failure){throw new IllegalStateException(failure);}return valid(target);
            }));
            var view=(JobResultComparisonReadService.Comparison)hooked.read(owner,ResultJdbcStoreTest.ROOT);assertEquals(rootRevision,view.jobRevision());assertEquals(1,view.currentContentRevision());
        }
        assertEquals(rootRevision+1,compare().jobRevision());assertEquals(2,compare().currentContentRevision());
    }
}
