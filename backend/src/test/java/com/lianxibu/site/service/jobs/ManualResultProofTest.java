package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real native persistence; report candidate selection and reference policy are fictional ports. */
class ManualResultProofTest {
    ResultJdbcStoreTest f;ResultJdbcStore strict;ResultApplicationService application;
    ResultJdbcStoreTest fixture(boolean profile){var value=profile?new ManualResultProofFixture():new ResultJdbcStoreTest();value.initialize(true);if(!profile)value.jdbc.execute("ALTER TABLE jobs ADD COLUMN report_mode VARCHAR(32)");return value;}
    @BeforeEach void setup(){setup(true);}
    void setup(boolean profile){f=fixture(profile);f.existingContent();assertEquals(Status.CONTENT_REVISION_CONFLICT,f.apply().status());
        var contents=new ContentReadService(new JdbcContentReads(f.source),f.tx,ContentReadService.denyUnverifiedReferences());
        strict=new ResultJdbcStore(f.source,f.identity,contents,target->f.jdbc.queryForObject("SELECT effective_valid FROM article_revisions WHERE id=?",Boolean.class,target.path("id").asText()),f.clock,"fictional-owner",true);
        f.store=strict;application=f.applicationService(strict);
    }
    long revision(){return new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).revision();}
    String hash(){return new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash();}
    Result apply(String key,long revision,Long contentRevision){return application.applyManual(strict.input(),hash(),revision,contentRevision,"Fictional verified manual application",true,f.action(key));}
    @Test void strictNativeWriterAppliesAndOriginalReceiptPrecedesLaterCurrentProof(){
        f.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-next-attempt',holder='fictional-next-holder',slot_revision=91,fencing_token=91");
        var slot=new JdbcWorkerState(f.source).slot();long original=revision();var value=apply("fictional-strict-manual",original,1L);
        assertEquals(Status.APPLIED,value.status());assertEquals(2L,value.contentRevision());assertEquals(original+1,revision());assertEquals(slot,new JdbcWorkerState(f.source).slot());
        assertEquals(1,new JdbcJobRecords(f.source).byId(ResultJdbcStoreTest.ROOT).callCount());assertEquals(1,f.sends);
        f.jdbc.update("UPDATE contents SET revision=revision+7,body_hash_v1=?",new byte[32]);f.jdbc.update("UPDATE article_revisions SET effective_valid=false");
        f.writes.execute(f.action("fictional-later-generation"),k->null,c->{new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent("fictional-later-intent",f.generation.command(false).logicalKey(),2,"daily_report",java.time.LocalDate.of(2026,1,1)),f.clock.instant());return true;});
        assertEquals(value,apply("fictional-strict-manual",original,1L));assertEquals(1,f.handlerCalls);assertEquals(slot,new JdbcWorkerState(f.source).slot());
    }
    @Test void missingNativeBodyProofAndMissingOriginalReportProfileCannotAuthorizeNewApplication(){
        f.jdbc.update("UPDATE contents SET body_revision=NULL,content_key_json=NULL");final long before=revision();
        assertThrows(RuntimeException.class,()->apply("fictional-legacy-body",before,1L));assertEquals(before,revision());assertEquals(0,f.handlerCalls);
        setup(false);final long current=revision();assertThrows(RuntimeException.class,()->apply("fictional-legacy-input",current,1L));assertEquals(current,revision());assertEquals(1L,f.jdbc.queryForObject("SELECT revision FROM contents",Long.class));
    }
    @Test void writerOnlyAssociationsKeepReadOnlyGateAndRejectNativeIdentityDamage(){
        var original=new JdbcOwnerRetainedResultReads(f.source);assertThrows(RuntimeException.class,()->original.readForApplication(ResultJdbcStoreTest.ROOT));
        var read=new TransactionTemplate(f.tx);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.execute(s->{assertNotNull(original.read(ResultJdbcStoreTest.ROOT));assertThrows(RuntimeException.class,()->original.readForApplication(ResultJdbcStoreTest.ROOT));return true;});
        f.writes.execute(f.action("fictional-proof-read"),k->null,c->{assertNotNull(original.readForApplication(ResultJdbcStoreTest.ROOT));assertThrows(RuntimeException.class,()->original.read(ResultJdbcStoreTest.ROOT));return true;});
        long before=revision();f.jdbc.update("UPDATE jobs SET logical_key='fictional-corrupt-key' WHERE id=?",ResultJdbcStoreTest.ROOT);
        assertThrows(RuntimeException.class,()->apply("fictional-corrupt-native",before,1L));assertEquals(before,revision());assertEquals(0,f.handlerCalls);
    }
    @Test void failureAfterBodyCasRollsBackAndCanRetryOriginalResultWithoutAnotherSend(){
        long before=revision();f.failDownstream=true;assertThrows(RuntimeException.class,()->apply("fictional-strict-rollback",before,1L));
        assertEquals(before,revision());assertEquals(1L,f.jdbc.queryForObject("SELECT revision FROM contents",Long.class));assertNull(new JdbcResultState(f.source).receipt("fictional-strict-rollback","fictional-owner",ResultJdbcStoreTest.ROOT,f.identity.attemptId()));
        f.failDownstream=false;assertEquals(Status.APPLIED,apply("fictional-strict-rollback",before,1L).status());assertEquals(1,f.sends);
    }
    @Test void currentRevisionsReferencesAndLatestGenerationCannotBeBypassed(){
        long before=revision();assertThrows(RuntimeException.class,()->apply("fictional-stale-root",before-1,1L));assertThrows(RuntimeException.class,()->apply("fictional-stale-content",before,0L));
        f.jdbc.update("UPDATE article_revisions SET effective_valid=false");assertThrows(RuntimeException.class,()->apply("fictional-invalid-reference",before,1L));f.jdbc.update("UPDATE article_revisions SET effective_valid=true");
        f.writes.execute(f.action("fictional-new-generation"),k->null,c->{new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent("fictional-new-intent",f.generation.command(false).logicalKey(),2,"daily_report",java.time.LocalDate.of(2026,1,1)),f.clock.instant());return true;});
        assertThrows(RuntimeException.class,()->apply("fictional-superseded",before,1L));assertEquals(before,revision());assertEquals(0,f.handlerCalls);assertEquals(1,f.sends);
    }
}
