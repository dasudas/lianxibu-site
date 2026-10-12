package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.domain.sources.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinator is an explicit double. This tests bounded orchestration, not permanent day proof. */
class SourceDayRunRegistrationCycleTest {
    private final LocalDate day=LocalDate.of(2026,9,20);
    private final Instant start=Instant.parse("2026-09-20T16:10:00Z");
    private final Clock clock=mock(Clock.class);
    private final SourceDayRunCoordinatorService coordinator=mock(SourceDayRunCoordinatorService.class);
    private final SourceDayRunCoordinatorService.Day original=mock(SourceDayRunCoordinatorService.Day.class);
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(start);when(coordinator.open(day)).thenReturn(original);
        when(original.requests()).thenReturn(requests(3));
        when(coordinator.reserve(eq(original),anyString())).thenAnswer(call->{var request=original.requests().stream().filter(r->r.source().id().equals(call.getArgument(1))).findFirst().orElseThrow();return new SourceDayRunRegistration.Reservation(request,1,request.registeredAt(),request.hash());});
    }
    private List<SourceDayRunRegistration.Request> requests(int count) {
        var all=new ArrayList<SourceDayRunRegistration.Request>();for(int i=0;i<count;i++) {
            var raw=new SourceDayRoster.RawSource("fictional-"+i,"agent",1,2,"production","enabled",start.plusSeconds(86400),false,true,"a".repeat(64));
            all.add(new SourceDayRunRegistration.Request(day,"b".repeat(64),start,raw,"fictional-run-"+i));
        }return List.copyOf(all);
    }
    @Test void completeOriginalIsOpenedBeforeBoundedFairIndependentReservations() {
        var cycle=new SourceDayRunRegistrationCycle(coordinator,clock,2);var first=cycle.tick();var second=cycle.tick();
        assertEquals(3,first.planned());assertEquals(2,first.attempted());assertEquals(2,first.verified());assertEquals(2,first.nextIndex());
        assertEquals(2,second.attempted());assertEquals(1,second.nextIndex());
        var order=inOrder(coordinator);order.verify(coordinator).open(day);order.verify(coordinator).reserve(original,"fictional-0");order.verify(coordinator).reserve(original,"fictional-1");order.verify(coordinator).open(day);order.verify(coordinator).reserve(original,"fictional-2");order.verify(coordinator).reserve(original,"fictional-0");
    }
    @Test void unavailableAndFailedSourceRemainPlannedAndDoNotBlockAnother() {
        doReturn(null).when(coordinator).reserve(original,"fictional-0");doThrow(new IllegalStateException("fictional unknown")).when(coordinator).reserve(original,"fictional-1");
        var outcome=new SourceDayRunRegistrationCycle(coordinator,clock,3).tick();assertEquals(3,outcome.planned());assertEquals(3,outcome.attempted());assertEquals(1,outcome.verified());assertEquals(1,outcome.unverified());assertEquals(1,outcome.failed());verify(coordinator).reserve(original,"fictional-2");assertEquals(3,original.requests().size());
    }
    @Test void windowAndDeadlineOrClockRollbackPreventNewReservations() {
        var cycle=new SourceDayRunRegistrationCycle(coordinator,clock,3);
        for(var instant:List.of(start.minusNanos(1000),Instant.parse("2026-09-20T20:30:00Z"))) {when(clock.instant()).thenReturn(instant);assertEquals(SourceDayRunRegistrationCycle.Status.SKIPPED,cycle.tick().status());}
        verifyNoInteractions(coordinator);
        when(clock.instant()).thenReturn(start,Instant.parse("2026-09-20T20:30:00Z"));assertEquals(0,cycle.tick().attempted());verify(coordinator).open(day);verify(coordinator,never()).reserve(any(),anyString());
        reset(clock);when(clock.instant()).thenReturn(start,start.minusNanos(1000));assertEquals(0,cycle.tick().attempted());verify(coordinator,never()).reserve(any(),anyString());
    }
    @Test void reentrantTickIsBusyAndExceptionsReleaseCycle() {
        var cycle=new SourceDayRunRegistrationCycle(coordinator,clock,1);
        doAnswer(call->{assertEquals(SourceDayRunRegistrationCycle.Status.BUSY,cycle.tick().status());return original;}).when(coordinator).open(day);
        assertEquals(1,cycle.tick().verified());
        doThrow(new IllegalStateException("fictional seal unavailable")).when(coordinator).open(day);assertThrows(IllegalStateException.class,cycle::tick);
        doReturn(original).when(coordinator).open(day);assertEquals(1,cycle.tick().verified());
    }
    @Test void emptyOriginalAndNewBusinessDayResetCursorWithoutTruncating() {
        var cycle=new SourceDayRunRegistrationCycle(coordinator,clock,1);assertEquals(1,cycle.tick().nextIndex());
        var tomorrow=requests(3).stream().map(r->new SourceDayRunRegistration.Request(day.plusDays(1),r.rosterHash(),start.plusSeconds(86400),new SourceDayRoster.RawSource(r.source().id(),"agent",1,2,"production","enabled",start.plusSeconds(172800),false,true,"a".repeat(64)),r.runId())).toList();
        when(original.requests()).thenReturn(tomorrow);when(clock.instant()).thenReturn(start.plusSeconds(86400));when(coordinator.open(day.plusDays(1))).thenReturn(original);assertEquals(1,cycle.tick().nextIndex());verify(coordinator,times(2)).reserve(original,"fictional-0");
        when(original.requests()).thenReturn(List.of());var empty=cycle.tick();assertEquals(0,empty.planned());assertEquals(0,empty.attempted());
    }
    @Test void callerTransactionAndInvalidBatchCannotStartWork() {
        for(int n:new int[]{0,1001})assertThrows(IllegalArgumentException.class,()->new SourceDayRunRegistrationCycle(coordinator,clock,n));
        TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->new SourceDayRunRegistrationCycle(coordinator,clock,1).tick());}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoInteractions(coordinator,clock);
    }
    @Test void stopDuringOneIndependentSourcePreventsTheNextReservation() {
        var running=new java.util.concurrent.atomic.AtomicBoolean(true);
        var cycle=new SourceDayRunRegistrationCycle(coordinator,clock,3);
        doAnswer(call->{running.set(false);return null;}).when(coordinator).reserve(original,"fictional-0");
        assertEquals(1,cycle.tick(running::get).attempted());verify(coordinator,never()).reserve(original,"fictional-1");
        reset(coordinator,clock);assertEquals(SourceDayRunRegistrationCycle.Status.SKIPPED,cycle.tick(running::get).status());verifyNoInteractions(coordinator,clock);
    }
}
