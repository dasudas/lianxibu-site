package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;

class VersionAvailabilityPollerTest {
    ResultJdbcStoreTest fixture;FrozenContentVersionService versions;ContentVersionKey version;
    @BeforeEach void setup(){fixture=new ResultJdbcStoreTest();fixture.fixture();install();}
    void install(){fixture.apply();versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(fixture.source));version=ContentVersionKey.fromJson(new JdbcResultState(fixture.source).receipt("fictional-apply","fictional-owner",ResultJdbcStoreTest.ROOT,fixture.identity.attemptId()).get("content_version"));}
    VersionAvailabilityPoller poller(int limit){return new VersionAvailabilityPoller(new JdbcValidityPropagation(fixture.source),new VersionAvailabilityService(versions,new JdbcVersionAvailability(fixture.source),fixture.writes,fixture.tx,fixture.clock),fixture.tx,limit);}
    ContentVersionKey archive(long revision){var proof=versions.find(version).orElseThrow();var next=new ContentVersionKey(version.contentKind(),version.contentId(),revision,version.bodyHash(),version.inputJobId());fixture.writes.execute(fixture.action("fictional-archive-"+revision),k->null,a->{new JdbcContentReviewEvidence(fixture.source).archive(next,proof.bodyJson(),proof.inputHash(),"fictional-owner",fixture.clock.instant());return true;});return next;}
    int observations(){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='content_availability'",Integer.class);}
    @Test void boundedWrapAndRestartFindLateInsertWithoutDuplicatingObservation(){
        var poll=poller(1);assertEquals(1,poll.poll().observed());assertEquals(1,observations());
        var late=archive(version.revision()+1);
        for(int i=0;i<5;i++){var step=poll.poll();assertTrue(step.scanned()<=1);assertEquals(0,step.failed());}
        assertTrue(versions.findAt(late,fixture.clock.instant()).isPresent());assertEquals(2,observations());
        var restarted=poller(50);assertEquals(2,restarted.poll().observed());assertEquals(2,observations());
    }
    @Test void corruptCandidateDoesNotStarveIndependentCommittedArchive(){
        var next=archive(version.revision()+1);fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='result_application'",new byte[32]);
        var result=poller(50).poll();assertEquals(2,result.scanned());assertEquals(1,result.failed());assertEquals(1,result.observed());assertTrue(versions.findAt(next,fixture.clock.instant()).isPresent());
    }
    @Test void unavailableWriteLeavesCandidateForRetryAndCallerTransactionIsRejected(){
        var poll=poller(50);fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_observer_failure CHECK(target_type IS NULL OR target_type<>'content_availability')");
        assertEquals(1,poll.poll().failed());assertEquals(0,observations());assertTrue(versions.find(version).isPresent());
        fixture.jdbc.execute(dropConstraint());assertEquals(1,poll.poll().observed());assertEquals(1,observations());
        assertThrows(IllegalStateException.class,()->fixture.writes.execute(fixture.action("fictional-nested-observer"),k->null,a->poll.poll()));
    }
    String dropConstraint(){return "ALTER TABLE jobs DROP CONSTRAINT fictional_observer_failure";}
    @Test void emptyDatabaseNeverWritesIdleReceipts(){fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='disposition'");int before=fixture.count("jobs");for(int i=0;i<3;i++)assertEquals(new VersionAvailabilityPoller.Outcome(0,0,0),poller(50).poll());assertEquals(before,fixture.count("jobs"));}
    @Test void enabledConfigurationConnectsActualPermanentObserver(){
        var scheduler=org.mockito.Mockito.mock(org.springframework.scheduling.TaskScheduler.class);
        org.mockito.Mockito.when(scheduler.scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(Runnable.class),org.mockito.ArgumentMatchers.any(Instant.class),org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(org.mockito.Mockito.mock(java.util.concurrent.ScheduledFuture.class));
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(com.lianxibu.site.config.VersionAvailabilityConfiguration.class)
            .withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.content.availability.enabled=true")
            .withBean(javax.sql.DataSource.class,()->fixture.source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->fixture.tx)
            .withBean("scheduleExecutor",org.springframework.scheduling.TaskScheduler.class,()->scheduler)
            .run(context->{assertNull(context.getStartupFailure());assertEquals(1,context.getBean(VersionAvailabilityPoller.class).poll().observed());assertEquals(1,observations());assertNotNull(versions.find(version).orElseThrow().availableAt());});
    }
}
