package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.domain.sources.SourceDayCollectionAdmission;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.sources.JdbcSourceDayRunRegistrations;
import java.time.*;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Full original day proof outside the writer; selected actual run/receipt proof inside it. */
public final class SourceDayRunClaimAdmissionService {
    private record Bound(SourceDayRunRegistration.Request request,Instant sealedAt){ }
    public static final class Prepared {
        private final SourceDayRunClaimAdmissionService owner;private final Map<String,Bound> runs;private final List<String> ids;private final Set<String> eligible,unverified;
        private Prepared(SourceDayRunClaimAdmissionService owner,Map<String,Bound> runs,Set<String> eligible){this.owner=owner;this.runs=Map.copyOf(runs);this.eligible=Set.copyOf(eligible);ids=eligible.stream().sorted().toList();var missing=new HashSet<>(runs.keySet());missing.removeAll(eligible);unverified=Set.copyOf(missing);}
        public List<String> runIds(){return ids;}
        public Set<String> unverifiedRunIds(){return unverified;}
        @Override public String toString(){return "PreparedSourceDayClaimFacts[redacted]";}
    }
    private final SourceDayCollectionAdmission.Api days;private final JdbcSourceDayRunRegistrations runs;private final TransactionTemplate reads;
    /** Compatibility constructor: no read scope means fail closed on every preparation. */
    public SourceDayRunClaimAdmissionService(SourceDayCollectionAdmission.Api days,JdbcSourceDayRunRegistrations runs){this.days=Objects.requireNonNull(days);this.runs=Objects.requireNonNull(runs);reads=null;}
    public SourceDayRunClaimAdmissionService(SourceDayCollectionAdmission.Api days,JdbcSourceDayRunRegistrations runs,PlatformTransactionManager manager){
        this.days=Objects.requireNonNull(days);this.runs=Objects.requireNonNull(runs);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Prepared prepare(Set<LocalDate> dates,Set<String> requestedSources){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("prepare source day claims outside transaction");
        if(reads==null)throw new IllegalStateException("source day claim read scope unavailable");
        Objects.requireNonNull(dates);Objects.requireNonNull(requestedSources);if(dates.size()>32||requestedSources.size()>1000)throw new SourceRunLeaseService.CapacityGuarded();
        var originalDates=Set.copyOf(dates);var sources=Set.copyOf(requestedSources);for(var source:sources)WireValues.id(source,true);var verified=new HashMap<String,Bound>();
        for(var day:originalDates.stream().sorted().toList()){
            var original=days.read(day);if(original==null)continue;if(!day.equals(original.day()))throw unavailable();
            for(var request:original.requests())if(sources.contains(request.source().id())){
                if(verified.putIfAbsent(request.runId(),new Bound(request,original.sealedAt()))!=null)throw unavailable();if(verified.size()>1000)throw new SourceRunLeaseService.CapacityGuarded();
            }
        }
        Set<String> eligible=reads.execute(status->{var result=new HashSet<String>();for(var bound:verified.values()){
            try{if(matches(bound,runs.lookup(bound.request())))result.add(bound.request().runId());}
            catch(IllegalStateException|IllegalArgumentException invalidOriginal){/* The complete original membership remains in verified. */}
        }return result;});
        return new Prepared(this,verified,Objects.requireNonNull(eligible));
    }
    public boolean allows(Prepared prepared,String runId){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("source day claim proof requires actual writer");
        return matchesPrepared(prepared,runId);
    }
    /** Original receipt lookup only; this does not grant a collector token or writer permission. */
    public boolean allowsObserved(Prepared prepared,String runId){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("source day original claim observation requires actual read-only transaction");
        return matchesPrepared(prepared,runId);
    }
    private boolean matchesPrepared(Prepared prepared,String runId){
        if(prepared==null||prepared.owner!=this)throw unavailable();var bound=prepared.runs.get(runId);if(bound==null||!prepared.eligible.contains(runId))return false;
        var original=runs.lookup(bound.request());if(original==null)return false;
        return matches(bound,original);
    }
    private static boolean matches(Bound bound,SourceDayRunRegistration.Reservation original){return original!=null&&bound.request().equals(original.request())&&original.generation()==1&&bound.request().hash().equals(original.requestHash())&&!original.reservedAt().isBefore(bound.sealedAt());}
    private static IllegalStateException unavailable(){return new IllegalStateException("original full source day claim proof unavailable");}
    @Override public String toString(){return "SourceDayRunClaimAdmissionService[redacted]";}
}
