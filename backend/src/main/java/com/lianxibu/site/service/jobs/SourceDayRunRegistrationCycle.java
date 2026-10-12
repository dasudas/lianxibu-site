package com.lianxibu.site.service.jobs;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Opens one complete original day and independently retries bounded source registrations. */
public final class SourceDayRunRegistrationCycle {
    public enum Status { SKIPPED,BUSY,PROGRESSED }
    public record Outcome(Status status,LocalDate day,int planned,int attempted,int verified,int unverified,int failed,int nextIndex) {
        public Outcome {
            Objects.requireNonNull(status);
            if(planned<0||planned>10000||attempted<0||attempted>planned||verified<0||unverified<0||failed<0||verified+unverified+failed!=attempted||nextIndex<0||planned>0&&nextIndex>=planned||planned==0&&nextIndex!=0)throw new IllegalArgumentException("source registration counts invalid");
        }
        @Override public String toString(){return "SourceDayRegistrationOutcome[redacted]";}
    }
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private final SourceDayRunCoordinatorService coordinator;
    private final Clock clock;
    private final int batch;
    private final AtomicBoolean inFlight=new AtomicBoolean();
    private LocalDate cursorDay;
    private int cursor;

    public SourceDayRunRegistrationCycle(SourceDayRunCoordinatorService coordinator,Clock clock,int batch) {
        this.coordinator=Objects.requireNonNull(coordinator);this.clock=Objects.requireNonNull(clock);
        if(batch<1||batch>1000)throw new IllegalArgumentException("bounded source registration batch required");this.batch=batch;
    }
    public Outcome tick() {return tick(()->true);}
    public Outcome tick(BooleanSupplier keepRunning) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("day cycle owns independent source transactions");
        Objects.requireNonNull(keepRunning);
        if(!inFlight.compareAndSet(false,true))return empty(Status.BUSY,null);
        try {
            if(!keepRunning.getAsBoolean())return empty(Status.SKIPPED,null);
            Instant beginning=clock.instant().truncatedTo(ChronoUnit.MICROS);
            var local=beginning.atZone(SHANGHAI);var day=local.toLocalDate().minusDays(1);
            if(local.toLocalTime().isBefore(LocalTime.of(0,10))||!local.toLocalTime().isBefore(LocalTime.of(4,30)))return empty(Status.SKIPPED,day);
            var original=coordinator.open(day);var requests=original.requests();
            if(requests.size()>10000)throw new GenerationFlowService.CapacityGuarded();
            var sourceIds=new HashSet<String>();var runIds=new HashSet<String>();
            for(var request:requests)if(request==null||!day.equals(request.day())||!sourceIds.add(request.source().id())||!runIds.add(request.runId()))throw new IllegalStateException("original source day plan inconsistent");
            if(!day.equals(cursorDay)){cursorDay=day;cursor=0;}
            int planned=requests.size();if(planned==0){cursor=0;return empty(Status.PROGRESSED,day);}
            if(cursor>=planned)cursor=0;
            int attempted=0,verified=0,unverified=0,failed=0;
            for(int i=0;i<Math.min(batch,planned);i++) {
                var current=clock.instant().truncatedTo(ChronoUnit.MICROS);
                if(!keepRunning.getAsBoolean()||Thread.currentThread().isInterrupted()||current.isBefore(beginning)||!current.isBefore(SourceDedupBarrier.deadline(day)))break;
                var request=requests.get(cursor);attempted++;
                try {if(coordinator.reserve(original,request.source().id())==null)unverified++;else verified++;}
                catch(RuntimeException unavailable){failed++;}
                cursor=(cursor+1)%planned;
            }
            return new Outcome(Status.PROGRESSED,day,planned,attempted,verified,unverified,failed,cursor);
        } finally {inFlight.set(false);}
    }
    private static Outcome empty(Status status,LocalDate day){return new Outcome(status,day,0,0,0,0,0,0);}
    @Override public String toString(){return "SourceDayRunRegistrationCycle[redacted]";}
}
