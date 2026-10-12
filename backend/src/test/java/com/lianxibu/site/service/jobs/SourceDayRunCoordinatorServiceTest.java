package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.jobs.JdbcSourceDayRosters;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SourceDayRunCoordinatorServiceTest {
    SourceDayCohortSealServiceTest fixture;SourceDayCohortSealService seals;SourceDayRunRegistration.Api runs;SourceDayRunCoordinatorService service;
    @BeforeEach void setup(){fixture=new SourceDayCohortSealServiceTest();fixture.setup();seals=fixture.service(()->evidence(),Clock.fixed(fixture.now,ZoneOffset.UTC));runs=mock(SourceDayRunRegistration.Api.class);service=new SourceDayRunCoordinatorService(seals,runs);}
    SourceDayRoster.Evidence evidence(){return new SourceDayRoster.Evidence(fixture.now,3,List.of(
        new SourceDayRoster.RawSource("fictional-a","agent",2,3,"production","disabled",fixture.now.plusSeconds(86400),false,true,"a".repeat(64)),
        new SourceDayRoster.RawSource("fictional-b","agent",2,3,"production","disabled",fixture.now.plusSeconds(86400),false,true,"b".repeat(64)),
        new SourceDayRoster.RawSource("fictional-unassigned",null,0,0,"draft","disabled",null,false,false,null)));}
    SourceDayRunRegistration.Reservation proof(SourceDayRunRegistration.Request request,Instant at){return new SourceDayRunRegistration.Reservation(request,1,at,request.hash());}
    @Test void dayIsSealedBeforeAnyIndependentReservationAndAllPlannedMembersRemain(){
        verifyNoInteractions(runs);var day=service.open(fixture.day);assertEquals(1,fixture.dispositions());assertEquals(2,day.requests().size());assertEquals(3,day.plan().directory().evidence().sourceCount());
        when(runs.reserve(any())).thenAnswer(invocation->{assertEquals(1,fixture.dispositions());return proof(invocation.getArgument(0),fixture.now);});
        var first=service.reserve(day,"fictional-a");assertEquals(day.requests().getFirst().runId(),first.request().runId());assertEquals(2,day.plan().cohort("agent").members().size());verify(runs,never()).reserve(day.requests().get(1));
        assertNotNull(service.reserve(day,"fictional-b"));assertEquals(1,fixture.dispositions());assertFalse(day.toString().contains("fictional-a"));assertThrows(UnsupportedOperationException.class,()->day.requests().clear());
    }
    @Test void originalLookupIsReturnedWithoutTryingAnotherReservation(){
        var day=service.open(fixture.day);var request=day.requests().getFirst();var original=proof(request,fixture.now);when(runs.lookup(request)).thenReturn(original);assertSame(original,service.reserve(day,"fictional-a"));verify(runs,never()).reserve(any());
    }
    @Test void unknownDisabledAndForeignDayCannotUseTheRunPort(){
        var day=service.open(fixture.day);assertThrows(RuntimeException.class,()->service.reserve(day,"fictional-unassigned"));assertThrows(RuntimeException.class,()->service.reserve(day,"fictional-absent"));assertThrows(RuntimeException.class,()->service.reserve(null,"fictional-a"));
        assertThrows(RuntimeException.class,()->new SourceDayRunCoordinatorService(seals,runs).reserve(day,"fictional-a"));verifyNoInteractions(runs);
        fixture.fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='snapshot_part' AND part_no=1");assertThrows(RuntimeException.class,()->service.open(fixture.day));verifyNoInteractions(runs);
    }
    @Test void unverifiedAndWrongRequestProofNeverCompleteOrReplaceOriginalMembership(){
        var day=service.open(fixture.day);assertNull(service.reserve(day,"fictional-a"));assertEquals(2,day.plan().requests().size());
        when(runs.reserve(day.requests().getFirst())).thenReturn(proof(day.requests().get(1),fixture.now));assertThrows(RuntimeException.class,()->service.reserve(day,"fictional-a"));assertEquals(2,day.plan().cohort("agent").members().size());
    }
    @Test void runReservedBeforeFullSealAndCallerOwnedTransactionAreRejected(){
        new SourceDayRosterRegistrationService(new JdbcSourceDayRosters(fixture.fixture.source),()->evidence(),fixture.fixture.writes,fixture.manager,Clock.fixed(fixture.now,ZoneOffset.UTC)).freeze(fixture.day);
        var late=fixture.service(()->evidence(),Clock.fixed(fixture.now.plusSeconds(10),ZoneOffset.UTC));var coordinator=new SourceDayRunCoordinatorService(late,runs);var day=coordinator.open(fixture.day);var request=day.requests().getFirst();when(runs.lookup(request)).thenReturn(proof(request,fixture.now.plusSeconds(9)));assertThrows(RuntimeException.class,()->coordinator.reserve(day,"fictional-a"));verify(runs,never()).reserve(any());
        var tx=new org.springframework.transaction.support.TransactionTemplate(fixture.manager);assertThrows(IllegalStateException.class,()->tx.execute(s->service.open(fixture.day)));assertThrows(IllegalStateException.class,()->tx.execute(s->coordinator.reserve(day,"fictional-a")));
    }
}
