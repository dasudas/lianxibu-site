package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import com.lianxibu.site.repository.jobs.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class SourceDayRosterRegistrationServiceTest {
    final LocalDate day=LocalDate.of(2026,9,20);final Instant now=Instant.parse("2026-09-20T16:10:00.000001Z");
    GenerationJdbcStoreTest fixture;JdbcSourceDayRosters storage;PlatformTransactionManager manager;int captured;
    @BeforeEach void setup(){fixture=new GenerationJdbcStoreTest();fixture.fixture();storage=new JdbcSourceDayRosters(fixture.source);manager=new DataSourceTransactionManager(fixture.source);}
    SourceDayRoster.Evidence original(int count){
        var rows=new ArrayList<SourceDayRoster.RawSource>();for(int i=0;i<count;i++)rows.add(new SourceDayRoster.RawSource("fictional-"+i,null,0,0,"draft","disabled",null,false,false,null));
        return new SourceDayRoster.Evidence(now,count,rows);
    }
    SourceDayRosterRegistrationService service(SourceDayRoster.Api input,Clock clock){return new SourceDayRosterRegistrationService(storage,input,fixture.writes,manager,clock);}
    SourceDayRoster.Api input(){return ()->{captured++;assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());return original(4);};}
    int rows(){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);}
    @Test void wholeDirectoryIsCapturedReadOnlyAndRegisteredThroughSharedWriter(){
        var service=service(input(),Clock.fixed(now,ZoneOffset.UTC));assertEquals(0,captured);assertEquals(0,rows());
        var result=service.freeze(day);assertEquals(1,captured);assertEquals(4,result.evidence().sourceCount());assertEquals(4,result.evidence().sources().stream().filter(s->s.category()==null).count());
        assertEquals(result.evidence().hash(),service.find(day).evidence().hash());assertEquals(0,fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void restartReturnsOriginalWithoutProviderClockOrNewRows(){
        var first=service(input(),Clock.fixed(now,ZoneOffset.UTC)).freeze(day);int rows=rows();
        var input=mock(SourceDayRoster.Api.class);var clock=mock(Clock.class);var restarted=service(input,clock);
        assertEquals(first.evidence().hash(),restarted.freeze(day).evidence().hash());assertEquals(rows,rows());verifyNoInteractions(input,clock);
    }
    @Test void unavailableAndCapacityNeverBecomeActualEmptyOrWriteAnything(){
        assertThrows(RuntimeException.class,()->service(()->null,Clock.fixed(now,ZoneOffset.UTC)).freeze(day));assertEquals(0,rows());
        assertThrows(SourceDayRoster.CapacityExceeded.class,()->service(()->{throw new SourceDayRoster.CapacityExceeded();},Clock.fixed(now,ZoneOffset.UTC)).freeze(day));assertEquals(0,rows());
        assertEquals(0,service(()->original(0),Clock.fixed(now,ZoneOffset.UTC)).freeze(day).evidence().sourceCount());
    }
    @Test void damagedOriginalNeverRecapturesAndOuterTransactionsAreRejected(){
        service(input(),Clock.fixed(now,ZoneOffset.UTC)).freeze(day);fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='snapshot_part' AND part_no=1");
        var untouched=mock(SourceDayRoster.Api.class);var clock=mock(Clock.class);var service=service(untouched,clock);assertThrows(RuntimeException.class,()->service.freeze(day));verifyNoInteractions(untouched,clock);
        var tx=new TransactionTemplate(manager);assertThrows(IllegalStateException.class,()->tx.execute(s->service.find(day)));assertThrows(IllegalStateException.class,()->tx.execute(s->service.freeze(day)));
    }
    @Test void cutoffBetweenCaptureAndWriterOrClockRollbackCannotCreateOriginalDay(){
        Clock clock=mock(Clock.class);when(clock.instant()).thenReturn(now,Instant.parse("2026-09-20T20:30:00.000000Z"));
        assertThrows(RuntimeException.class,()->service(input(),clock).freeze(day));assertEquals(0,rows());
        when(clock.instant()).thenReturn(now,now.minusNanos(1000));assertThrows(RuntimeException.class,()->service(input(),clock).freeze(day));assertEquals(0,rows());
    }
    @Test void actualCommitWithLostAcknowledgementReturnsOriginalWithoutRecapture(){
        var manager=new DataSourceTransactionManager(fixture.source){boolean lost;
            @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new TransactionSystemException("fictional commit acknowledgement lost",new SQLException("fictional transport","08006"));}}
        };
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(fixture.source),manager,()->25,n->{});
        var service=new SourceDayRosterRegistrationService(storage,input(),writes,manager,Clock.fixed(now,ZoneOffset.UTC));
        var saved=service.freeze(day);assertEquals(1,captured);assertEquals(4,saved.evidence().sourceCount());assertEquals(saved.evidence().hash(),service.freeze(day).evidence().hash());assertEquals(1,captured);
    }
}
