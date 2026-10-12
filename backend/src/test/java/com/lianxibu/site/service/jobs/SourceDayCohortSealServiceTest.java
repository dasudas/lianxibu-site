package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import com.lianxibu.site.repository.jobs.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.node.ObjectNode;

class SourceDayCohortSealServiceTest {
    final LocalDate day=LocalDate.of(2026,9,20);final Instant now=Instant.parse("2026-09-20T16:10:00.000001Z");
    GenerationJdbcStoreTest fixture;JdbcSourceDayRosters directories;JdbcSourceDayCohortSeals archives;PlatformTransactionManager manager;int captures;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){fixture=fixture();fixture.fixture();directories=new JdbcSourceDayRosters(fixture.source);archives=new JdbcSourceDayCohortSeals(fixture.source);manager=new DataSourceTransactionManager(fixture.source);}
    SourceDayRoster.Evidence evidence(int count){
        var rows=new ArrayList<SourceDayRoster.RawSource>();for(int i=0;i<count;i++)rows.add(new SourceDayRoster.RawSource("fictional-"+i,i%2==0?null:"agent",0,0,"draft","disabled",null,false,false,null));return new SourceDayRoster.Evidence(now,count,rows);
    }
    SourceDayRoster.Api input(){return ()->{captures++;return evidence(4);};}
    SourceDayCohortSealService service(SourceDayRoster.Api input,Clock clock){return service(input,clock,fixture.writes,manager);}
    SourceDayCohortSealService service(SourceDayRoster.Api input,Clock clock,SharedWriteExecutor writes,PlatformTransactionManager tx){
        var registry=new SourceDayRosterRegistrationService(directories,input,writes,tx,clock);return new SourceDayCohortSealService(registry,directories,archives,writes,tx,clock);
    }
    int dispositions(){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='disposition'",Integer.class);}
    @Test void completeOriginalSealedThroughActualSharedWriterRetainsUnknownAndFiveCategories(){
        var service=service(input(),Clock.fixed(now,ZoneOffset.UTC));assertNull(service.find(day));var result=service.seal(day);assertEquals(1,captures);assertEquals(4,result.plan().directory().evidence().sourceCount());assertEquals(5,result.plan().cohorts().size());assertEquals(2,result.plan().unassignedSourceIds().size());
        assertEquals(result.plan().bundleHash(),service.find(day).plan().bundleHash());assertEquals(now,result.sealedAt());assertEquals(1,dispositions());assertEquals(0,fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void restartAndRepeatedSealNeedNoCurrentInputClockOrNewRows(){
        var first=service(input(),Clock.fixed(now,ZoneOffset.UTC)).seal(day);int rows=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);
        var input=mock(SourceDayRoster.Api.class);var clock=mock(Clock.class);var restarted=service(input,clock);assertEquals(first.plan().bundleJson(),restarted.seal(day).plan().bundleJson());assertEquals(now,restarted.find(day).sealedAt());verifyNoInteractions(input,clock);assertEquals(rows,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
    }
    @Test void rehashedManifestCannotSubstituteForOriginalDirectoryAndNeverRecaptures()throws Exception{
        var first=service(input(),Clock.fixed(now,ZoneOffset.UTC)).seal(day);var body=(ObjectNode)CanonicalJson.parse(first.plan().bundleJson());((ObjectNode)body.get("manifest")).put("source_count",5);String json=new String(CanonicalJson.bytes(body),StandardCharsets.UTF_8);
        fixture.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE record_kind='disposition'",json,MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(new TransactionTemplate(manager).execute(s->archives.read(day)));
        var input=mock(SourceDayRoster.Api.class);var clock=mock(Clock.class);var restarted=service(input,clock);assertThrows(RuntimeException.class,()->restarted.find(day));assertThrows(RuntimeException.class,()->restarted.seal(day));verifyNoInteractions(input,clock);assertEquals(1,dispositions());
    }
    @Test void actualEmptyIsSealedButUnknownOrBadOriginalIsNeverRepaired(){
        assertThrows(RuntimeException.class,()->service(()->null,Clock.fixed(now,ZoneOffset.UTC)).seal(day));assertEquals(0,dispositions());
        var empty=service(()->evidence(0),Clock.fixed(now,ZoneOffset.UTC)).seal(day);assertEquals(0,empty.plan().directory().evidence().sourceCount());assertEquals(5,empty.plan().cohorts().size());
        fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='snapshot_part' AND part_no=1");var input=mock(SourceDayRoster.Api.class);var clock=mock(Clock.class);assertThrows(RuntimeException.class,()->service(input,clock).seal(day));verifyNoInteractions(input,clock);
    }
    @Test void outerTransactionsAndWriterCutoffCannotCreateSeal(){
        var registry=new SourceDayRosterRegistrationService(directories,input(),fixture.writes,manager,Clock.fixed(now,ZoneOffset.UTC));registry.freeze(day);
        var clock=mock(Clock.class);when(clock.instant()).thenReturn(now,Instant.parse("2026-09-20T20:30:00.000000Z"));var service=service(input(),clock);assertThrows(RuntimeException.class,()->service.seal(day));assertEquals(0,dispositions());
        when(clock.instant()).thenReturn(now,now.minusNanos(1000));assertThrows(RuntimeException.class,()->service.seal(day));assertEquals(0,dispositions());
        var tx=new TransactionTemplate(manager);assertThrows(IllegalStateException.class,()->tx.execute(s->service.find(day)));assertThrows(IllegalStateException.class,()->tx.execute(s->service.seal(day)));
    }
    @Test void actualCommitWithLostAcknowledgementReadsOneOriginalSealWithoutRecapture(){
        new SourceDayRosterRegistrationService(directories,input(),fixture.writes,manager,Clock.fixed(now,ZoneOffset.UTC)).freeze(day);
        var tx=new DataSourceTransactionManager(fixture.source){boolean lost;
            @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new TransactionSystemException("fictional seal acknowledgement lost",new SQLException("fictional transport","08006"));}}
        };
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(fixture.source),tx,()->25,n->{});var input=mock(SourceDayRoster.Api.class);var service=service(input,Clock.fixed(now,ZoneOffset.UTC),writes,tx);var first=service.seal(day);assertEquals(first.plan().bundleHash(),service.seal(day).plan().bundleHash());assertEquals(1,dispositions());verifyNoInteractions(input);assertEquals(1,captures);
    }
    TransactionTemplate rr(){var tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx;}
    @Test void borrowedOriginalReaderAndFullVerificationKeepActualEmptyAndUnknownDistinct(){
        var service=service(()->evidence(0),Clock.fixed(now,ZoneOffset.UTC));assertNull(rr().execute(s->service.readObserved(day)));var original=service.seal(day);
        assertSame(original,rr().execute(s->service.verifyObserved(original)));assertEquals(original.plan().bundleJson(),rr().execute(s->service.readObserved(day)).plan().bundleJson());
        var restart=service(mock(SourceDayRoster.Api.class),mock(Clock.class));assertSame(original,rr().execute(s->restart.verifyObserved(original)));assertEquals(0,original.plan().requests().size());
    }
    @Test void fullObservedSealRejectsChangedNativeTimestampAndMissingOriginalDirectory(){
        var service=service(input(),Clock.fixed(now,ZoneOffset.UTC));var original=service.seal(day);var later=java.time.LocalDateTime.ofInstant(now.plusSeconds(1),ZoneOffset.UTC);
        fixture.jdbc.update("UPDATE jobs SET acted_at=?,created_at=?,updated_at=?,eligible_at=? WHERE record_kind='disposition'",later,later,later,later);
        assertNotNull(service.find(day));assertThrows(IllegalStateException.class,()->rr().execute(s->service.verifyObserved(original)));
        fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='snapshot_part' AND part_no=1");assertThrows(RuntimeException.class,()->rr().execute(s->service.readObserved(day)));
    }
    @Test void borrowedReadsRequireExplicitReadonlyRepeatableReadBeforeAnyIo(){
        var input=mock(SourceDayRoster.Api.class);var clock=mock(Clock.class);var d=mock(JdbcSourceDayRosters.class);var seals=mock(JdbcSourceDayCohortSeals.class);var registration=mock(SourceDayRosterRegistrationService.class);var writes=mock(SharedWriteExecutor.class);var txManager=mock(PlatformTransactionManager.class);
        var service=new SourceDayCohortSealService(registration,d,seals,writes,txManager,clock);assertThrows(IllegalStateException.class,()->service.readObserved(day));assertThrows(IllegalStateException.class,()->service.verifyObserved(null));
        var rc=new TransactionTemplate(manager);rc.setReadOnly(true);rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(IllegalStateException.class,()->rc.execute(s->service.readObserved(day)));
        var writer=new TransactionTemplate(manager);writer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);assertThrows(IllegalStateException.class,()->writer.execute(s->service.readObserved(day)));
        var implicit=new TransactionTemplate(manager);implicit.setReadOnly(true);assertThrows(IllegalStateException.class,()->implicit.execute(s->service.readObserved(day)));
        verifyNoInteractions(input,clock,d,seals,registration,writes,txManager);
    }
}
