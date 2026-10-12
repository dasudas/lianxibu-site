package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ResultApplicationFactoryTest {
 ResultJdbcStoreTest f;ResultApplicationFactory factory;
 ResultJdbcStoreTest fixture(){return new ResultJdbcStoreTest();}
 @BeforeEach void setup(){f=fixture();f.fixture();f.jdbc.execute("ALTER TABLE article_revisions ADD validity_revision BIGINT NOT NULL DEFAULT 0");f.jdbc.execute("ALTER TABLE jobs ADD validity_revision BIGINT");f.jdbc.execute("ALTER TABLE jobs ADD reverses_disposition_id VARCHAR(64)");factory=new ResultApplicationFactory(f.source,f.tx,f.clock);var app=factory.forAttempt(f.identity);assertEquals(f.store.input(),app.input());f.service=app.service();}
 @Test void actualFactoryCommitsReportAndPeriodIntentionsOnce(){assertEquals(ResultApplicationService.Status.APPLIED,f.apply().status());assertEquals(1,f.count("contents"));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='report_result_ready'",Integer.class));assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='generation_intent' AND task_type IN ('weekly_report','monthly_report')",Integer.class));f.apply();assertEquals("free",new JdbcWorkerState(f.source).slot().state());assertEquals(1,f.sends);}
 @Test void missingReferenceRetainsOriginalResultWithoutWritingContent(){f.jdbc.update("DELETE FROM article_revisions");assertEquals(ResultApplicationService.Status.INVALID_EVIDENCE,f.apply().status());assertEquals(0,f.count("contents"));assertNotNull(new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash());assertEquals("free",new JdbcWorkerState(f.source).slot().state());}
 @Test void changedOriginalIdentityCannotAssembleAnApplication(){var old=f.identity;var changed=new WorkerAuthorizationService.Identity(old.rootId(),old.attemptId(),old.dispatchToken(),old.slotRevision()+1,old.fencing(),old.leaseUntil(),old.frozenRequestJson(),old.inputHash());assertThrows(RuntimeException.class,()->factory.forAttempt(changed));assertEquals(0,f.count("contents"));}
 @Test void disabledConfigurationDoesNotDemandDatabaseAndEnabledConnectsFactory(){
  new ApplicationContextRunner().withUserConfiguration(ResultApplicationConfiguration.class).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(AttemptOutcomeService.Applications.class).isEmpty());});
  new ApplicationContextRunner().withUserConfiguration(ResultApplicationConfiguration.class).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true").withBean(javax.sql.DataSource.class,()->f.source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->f.tx).run(c->{assertNull(c.getStartupFailure());assertInstanceOf(ResultApplicationFactory.class,c.getBean(AttemptOutcomeService.Applications.class));assertEquals(f.store.input(),c.getBean(AttemptOutcomeService.Applications.class).forAttempt(f.identity).input());});
 }
}
