package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import java.time.LocalDate;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One original full seal precedes independently committed source run registrations. */
public final class SourceDayRunCoordinatorService {
    public static final class Day {
        private final SourceDayRunCoordinatorService owner;private final SourceDayCohortSealService.Sealed sealed;
        private final Map<String,SourceDayRunRegistration.Request> requests;
        private Day(SourceDayRunCoordinatorService owner,SourceDayCohortSealService.Sealed sealed){
            this.owner=owner;this.sealed=sealed;var bySource=new LinkedHashMap<String,SourceDayRunRegistration.Request>();
            for(var request:sealed.plan().requests())if(bySource.putIfAbsent(request.source().id(),request)!=null)throw unavailable();requests=Collections.unmodifiableMap(bySource);
        }
        public SourceDayCohortPlan plan(){return sealed.plan();}public List<SourceDayRunRegistration.Request> requests(){return List.copyOf(requests.values());}
        @Override public String toString(){return "OriginalSourceDayRegistrationPlan[redacted]";}
    }
    private final SourceDayCohortSealService seals;private final SourceDayRunRegistration.Api runs;
    public SourceDayRunCoordinatorService(SourceDayCohortSealService seals,SourceDayRunRegistration.Api runs){this.seals=Objects.requireNonNull(seals);this.runs=Objects.requireNonNull(runs);}
    public Day open(LocalDate day){outside();return new Day(this,seals.seal(day));}
    public SourceDayRunRegistration.Reservation reserve(Day day,String sourceId){
        outside();if(day==null||day.owner!=this||sourceId==null)throw unavailable();var request=day.requests.get(sourceId);if(request==null)throw unavailable();
        var result=runs.lookup(request);if(result==null)result=runs.reserve(request);if(result==null)return null;
        if(!request.equals(result.request())||result.generation()!=1||!request.hash().equals(result.requestHash())||result.reservedAt().isBefore(day.sealed.sealedAt()))throw unavailable();return result;
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("source day run coordinator requires independent transactions");}
    private static IllegalStateException unavailable(){return new IllegalStateException("original sealed source day registration unavailable");}
    @Override public String toString(){return "SourceDayRunCoordinatorService[redacted]";}
}
