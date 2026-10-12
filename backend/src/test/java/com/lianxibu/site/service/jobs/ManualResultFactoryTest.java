package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.ResultApplicationFactory;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import org.junit.jupiter.api.*;

class ManualResultFactoryTest {
    ResultJdbcStoreTest f;ResultApplicationFactory factory;final Actor owner=new Actor("fictional-owner","owner");
    ResultJdbcStoreTest fixture(){var value=new ManualResultProofFixture();value.initialize(true);return value;}
    @BeforeEach void setup(){f=fixture();f.jdbc.execute("ALTER TABLE article_revisions ADD validity_revision BIGINT NOT NULL DEFAULT 0");f.jdbc.execute("ALTER TABLE jobs ADD validity_revision BIGINT");f.jdbc.execute("ALTER TABLE jobs ADD reverses_disposition_id VARCHAR(64)");
        factory=new ResultApplicationFactory(f.source,f.tx,f.clock);f.existingContent();f.service=factory.forAttempt(f.identity).service();assertEquals(Status.CONTENT_REVISION_CONFLICT,f.apply().status());}
    long revision(){return new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).revision();}
    Result apply(String key,long expected){var value=factory.forManual(f.identity,owner);return value.service().applyManual(value.input(),new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash(),expected,1L,"Fictional owner reviewed result",true,f.action(key));}
    @Test void actualOwnerFactoryCommitsReportDownstreamAndPreservesReplacementSlot(){
        long original=revision();f.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-new-attempt',holder='fictional-new-holder',slot_revision=91,fencing_token=91");var slot=new JdbcWorkerState(f.source).slot();
        f.tx.ackLost=true;var applied=apply("fictional-owner-manual",original);assertEquals(Status.APPLIED,applied.status());assertEquals(2L,applied.contentRevision());assertEquals(slot,new JdbcWorkerState(f.source).slot());
        assertEquals("draft",f.jdbc.queryForObject("SELECT state FROM contents",String.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='report_result_ready'",Integer.class));assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='generation_intent' AND task_type IN ('weekly_report','monthly_report')",Integer.class));
        assertNotNull(new JdbcResultState(f.source).receipt("fictional-owner-manual",owner.userId(),ResultJdbcStoreTest.ROOT,f.identity.attemptId()));
        assertThrows(RuntimeException.class,()->new JdbcResultState(f.source).receipt("fictional-owner-manual","analysis-worker",ResultJdbcStoreTest.ROOT,f.identity.attemptId()));
        assertEquals(applied,apply("fictional-owner-manual",original));assertEquals(1,f.sends);assertEquals(1,new JdbcJobRecords(f.source).byId(ResultJdbcStoreTest.ROOT).callCount());
    }
    @Test void durableOriginalReplyDoesNotReadLaterInvalidCurrentBodyOrReference(){long original=revision();var applied=apply("fictional-manual-replay",original);f.jdbc.update("UPDATE contents SET body_hash_v1=?,revision=revision+8",new byte[32]);f.jdbc.update("UPDATE article_revisions SET effective_valid=false");assertEquals(applied,apply("fictional-manual-replay",original));assertEquals(1,f.sends);}
    @Test void ownerAndOriginalIdentityAreMandatoryBeforeManualAssembly(){
        assertThrows(RuntimeException.class,()->factory.forManual(f.identity,null));assertThrows(RuntimeException.class,()->factory.forManual(f.identity,new Actor("fictional-reader","user")));
        var old=f.identity;var changed=new WorkerAuthorizationService.Identity(old.rootId(),old.attemptId(),old.dispatchToken(),old.slotRevision()+1,old.fencing(),old.leaseUntil(),old.frozenRequestJson(),old.inputHash());assertThrows(RuntimeException.class,()->factory.forManual(changed,owner));assertEquals(1L,f.jdbc.queryForObject("SELECT revision FROM contents",Long.class));
    }
    @Test void currentNativeProofCannotUseLegacyCompatibilityAndFailedApplicationHasNoDownstream(){long original=revision();f.jdbc.update("UPDATE contents SET body_revision=NULL,content_key_json=NULL");assertThrows(RuntimeException.class,()->apply("fictional-owner-legacy",original));assertEquals(original,revision());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='report_result_ready'",Integer.class));assertEquals(1,f.sends);}
}
