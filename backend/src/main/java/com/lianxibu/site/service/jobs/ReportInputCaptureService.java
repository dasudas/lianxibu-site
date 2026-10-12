package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Native readonly routing only. Writer admission and preparation have a separate explicit boundary. */
public final class ReportInputCaptureService {
    public record Scope(String logicalKey,long generation,Stage stage,LocalDate day) {
        public Scope {
            if(stage==null||!stage.report()||day==null||generation<1||generation>com.lianxibu.site.domain.protocol.CanonicalJson.MAX_SAFE_INTEGER
                ||!("report:"+stage.name().toLowerCase(Locale.ROOT).replace("_report","")+":"+day).equals(logicalKey))throw invalid();
        }
    }
    public static final class Captured {
        private final Object issuer;private final Scope scope;private final Instant cutoff;
        private final DailyReportInputComponentService.Captured daily;private final ReportPeriodInputComponentService.Captured period;
        private final SourceProof proof;private final List<Dependency> dependencies;private final Map<String,List<String>> targets;private final long bytes;private final boolean supplement;
        private Captured(Object issuer,Scope scope,Instant cutoff,DailyReportInputComponentService.Captured daily,
                         ReportPeriodInputComponentService.Captured period,SourceProof proof,List<Dependency> dependencies,
                         Map<String,List<String>> targets,long bytes,boolean supplement){this.issuer=issuer;this.scope=scope;this.cutoff=cutoff;this.daily=daily;this.period=period;
            this.proof=proof;this.dependencies=List.copyOf(dependencies);var copies=new TreeMap<String,List<String>>();targets.forEach((key,value)->copies.put(key,List.copyOf(value)));this.targets=Collections.unmodifiableMap(copies);this.bytes=bytes;this.supplement=supplement;}
        public Scope scope(){return scope;}public Instant cutoff(){return cutoff;}public boolean explicitSupplement(){return supplement;}public SourceProof sourceProof(){return proof;}
        public List<Dependency> dependencies(){return dependencies;}public Map<String,List<String>> referenceTargetsByEvidence(){return targets;}
        public long originalByteSize(){return bytes;}
        @Override public String toString(){return "CapturedNativeReportInputs[redacted]";}
    }
    private final Object issuer=new Object();private final DailyReportInputComponentService daily;private final ReportPeriodInputComponentService period;
    private final TransactionTemplate reads;
    public ReportInputCaptureService(DailyReportInputComponentService daily,ReportPeriodInputComponentService period,PlatformTransactionManager manager){
        this.daily=Objects.requireNonNull(daily);this.period=Objects.requireNonNull(period);reads=new TransactionTemplate(Objects.requireNonNull(manager));
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Captured capture(Scope scope,Instant cutoff){return capture(scope,cutoff,false);}
    /** Explicit generation two or later. Source facts alone do not grant manual operation permission. */
    public Captured captureSupplement(Scope scope,Instant cutoff){if(scope==null||scope.generation()<2)throw invalid();return capture(scope,cutoff,true);}
    private Captured capture(Scope scope,Instant cutoff,boolean supplement){
        if(TransactionSynchronizationManager.isActualTransactionActive()||scope==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        if(scope.stage()==Stage.DAILY_REPORT){var value=supplement?daily.captureSupplement(scope.day(),cutoff):daily.capture(scope.day(),cutoff);if(value==null)return null;
            return reads.execute(s->{daily.verifyObserved(value);return checked(new Captured(issuer,scope,cutoff,value,null,value.sourceProof(),value.dependencies(),value.referenceTargetsByEvidence(),value.originalByteSize(),supplement));});}
        var value=supplement?period.captureSupplement(scope.stage(),scope.day(),cutoff):period.capture(scope.stage(),scope.day(),cutoff);if(value==null)throw invalid();
        return reads.execute(s->{period.verifyObserved(value);var targets=new TreeMap<String,List<String>>();value.referenceTargets().forEach((key,target)->targets.put(key,List.of(target)));
            return checked(new Captured(issuer,scope,cutoff,null,value,value.sourceProof(),value.dependencies(),targets,value.originalByteSize(),supplement));});
    }
    public Captured verifyObserved(Captured value){
        readonly();if(value==null||value.issuer!=issuer)throw invalid();checked(value);
        if(value.daily!=null)daily.verifyObserved(value.daily);else period.verifyObserved(value.period);return value;
    }
    /** The callback borrows readonly RR. It cannot be injected into a writable GenerationFlow transaction. */
    public Source asReadonlySource(Captured value){
        if(value==null||value.issuer!=issuer)throw invalid();
        return (command,cutoff)->{readonly();if(command==null||command.explicitSupplement()!=value.supplement||command.sourceId()!=null||command.stagePart()!=0
            ||!value.scope.logicalKey().equals(command.logicalKey())||value.scope.generation()!=command.generation()
            ||value.scope.stage()!=command.stage()||!value.scope.day().equals(command.businessDate())||!value.cutoff.equals(cutoff))throw invalid();return verifyObserved(value).sourceProof();};
    }
    private Captured checked(Captured value){
        readonly();if((value.daily==null)==(value.period==null)||!value.proof.complete()||!value.cutoff.equals(value.proof.cutoff())
            ||!value.scope.day().plusDays(1).equals(value.proof.periodEnd())||value.bytes<0||value.bytes>32L*1024*1024)throw invalid();
        var ids=new HashSet<String>();for(var candidate:value.proof.candidates())if(candidate.evidence()!=null&&!ids.add(candidate.evidence().id()))throw invalid();
        if(!ids.equals(value.targets.keySet()))throw invalid();var deps=new HashSet<String>();for(var dependency:value.dependencies){if(dependency==null||!"succeeded".equals(dependency.state())
            ||dependency.completedAt()==null||dependency.completedAt().isAfter(value.cutoff)||!deps.add(dependency.id()))throw invalid();}
        if(!deps.equals(new HashSet<>(value.proof.verifiedDependencyIds())))throw invalid();
        for(var candidate:value.proof.candidates())if(candidate.evidence()!=null&&!deps.containsAll(candidate.evidence().references()))throw invalid();return value;
    }
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native report input capture unavailable");}
    @Override public String toString(){return "ReportInputCaptureService[redacted]";}
}
