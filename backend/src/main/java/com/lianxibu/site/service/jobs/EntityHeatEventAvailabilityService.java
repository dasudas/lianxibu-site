package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual committed event read precedes first observation. No writer time is substituted for visibility. */
public final class EntityHeatEventAvailabilityService {
    public static final class Available {
        private final EntityHeatEventProofService.Proven event;private final Instant at;
        private Available(EntityHeatEventProofService.Proven event,Instant at){this.event=event;this.at=at;}
        public EntityHeatEventProofService.Proven event(){return event;}public Instant availableAt(){return at;}
        @Override public String toString(){return "AvailableOriginalEntityHeatEvent[redacted]";}
    }
    private final EntityHeatEventProofService events;private final JdbcEntityHeatAvailability first;private final SharedWriteExecutor writes;private final Clock clock;private final TransactionTemplate reads;
    public EntityHeatEventAvailabilityService(EntityHeatEventProofService events,JdbcEntityHeatAvailability first,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){this.events=Objects.requireNonNull(events);this.first=Objects.requireNonNull(first);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public Available find(String id){outside();return reads.execute(s->read(id,null));}
    /** Full original first, including a late observation, in the caller's RR snapshot. */
    public Available readObserved(String id){readonly();return read(id,null);}
    public Available readObserved(String id,Instant cutoff){readonly();if(cutoff==null||cutoff.getNano()%1000!=0)throw invalid();return read(id,cutoff);}
    private Available read(String id,Instant cutoff){readonly();var event=events.readObserved(id);var original=first.read(id);if(event==null){if(original!=null)throw invalid();return null;}if(original==null)return null;if(!event.hasCompleteTouchedFormat()||event.snapshots().isEmpty()||!original.eventHash().equals(JdbcEntityHeatAvailability.eventHash(event.event()))||original.availableAt().isBefore(event.event().actedAt()))throw invalid();if(cutoff!=null&&original.availableAt().isAfter(cutoff))return null;return new Available(event,original.availableAt());}
    public Available capture(String id){outside();var previous=find(id);if(previous!=null)return previous;var event=events.find(id);if(event==null||!event.hasCompleteTouchedFormat()||event.snapshots().isEmpty())return null;Instant at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var prepared=JdbcEntityHeatAvailability.prepare(event.event(),at);String hash=JdbcEntityHeatAvailability.eventHash(event.event());
        writes.execute(new SharedWriteExecutor.ActionContext(JdbcEntityHeatAvailability.action(id),id,hash),key->{var original=first.read(id);if(original!=null&&!hash.equals(original.eventHash()))throw invalid();return original;},context->{if(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).isBefore(at))throw invalid();return first.save(prepared);});var saved=find(id);if(saved==null)throw invalid();return saved;
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original entity heat event visibility unavailable");}
}
