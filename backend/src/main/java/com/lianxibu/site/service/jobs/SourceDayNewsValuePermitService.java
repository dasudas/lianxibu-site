package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;

/** Additional original day permission. Existing own P1, member and request qualifications remain required. */
public final class SourceDayNewsValuePermitService {
    public static final class Captured {
        private final SourceDayNewsValuePermitService owner;private final FrozenSourceDayDedupDependency dependency;private final FrozenSourceDedupDependency own;
        private final JdbcSourceDayDedupClosures.Archive archive;private final SourceDayRunClaimAdmissionService.Prepared registered;private final SourceDedupPartialDependencyPermitService.Captured partial;
        private Captured(SourceDayNewsValuePermitService owner,FrozenSourceDayDedupDependency dependency,FrozenSourceDedupDependency own,JdbcSourceDayDedupClosures.Archive archive,SourceDayRunClaimAdmissionService.Prepared registered,SourceDedupPartialDependencyPermitService.Captured partial){this.owner=owner;this.dependency=dependency;this.own=own;this.archive=archive;this.registered=registered;this.partial=partial;}
        public FrozenSourceDayDedupDependency dependency(){return dependency;}
        @Override public String toString(){return "CapturedOriginalNewsDayPermission[redacted]";}
    }
    private final SourceDayNewsDedupBarrierService barriers;private final SourceDayRunClaimAdmissionService registrations;private final JdbcSourceDayDedupClosures archives;
    private final SourceDedupStageProofService proofs;private final SourceDedupStageAvailabilityService availability;private final SourceDedupDependencyService dependencies;
    private final SourceDedupPartialDependencyPermitService partials;private final TransactionTemplate reads;
    public SourceDayNewsValuePermitService(SourceDayNewsDedupBarrierService barriers,SourceDayRunClaimAdmissionService registrations,JdbcSourceDayDedupClosures archives,SourceDedupStageProofService proofs,SourceDedupStageAvailabilityService availability,SourceDedupDependencyService dependencies,SourceDedupPartialDependencyPermitService partials,PlatformTransactionManager manager){
        this.barriers=Objects.requireNonNull(barriers);this.registrations=Objects.requireNonNull(registrations);this.archives=Objects.requireNonNull(archives);this.proofs=Objects.requireNonNull(proofs);this.availability=Objects.requireNonNull(availability);this.dependencies=Objects.requireNonNull(dependencies);this.partials=Objects.requireNonNull(partials);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    /** May close the actual full day first. Null preserves an unclosed or unregistered prerequisite. */
    public Captured capture(JsonNode ownProfile,Instant cutoff){
        outside();var own=own(ownProfile,cutoff);var key=own.toJson().get("stage_key");var closed=barriers.close(WireValues.businessDate(key.get("business_date")));return closed==null?null:captureOriginal(closed,own,cutoff,null);
    }
    /** Reconstructs a capability from original SQL, without a current clock or new closure. */
    public Captured captureExisting(JsonNode dayProfile,JsonNode ownProfile,Instant cutoff){
        outside();var own=own(ownProfile,cutoff);var expected=FrozenSourceDayDedupDependency.read(JdbcFrozenInputs.canonical(dayProfile),own,cutoff);
        var closed=barriers.find(WireValues.businessDate(own.toJson().get("stage_key").get("business_date")));if(closed==null)throw invalid();var captured=captureOriginal(closed,own,cutoff,expected.json());if(captured==null)throw invalid();return captured;
    }
    private Captured captureOriginal(SourceDayNewsDedupBarrierService.Closed closed,FrozenSourceDedupDependency own,Instant cutoff,String expected){
        var key=own.toJson().get("stage_key");LocalDate day=WireValues.businessDate(key.get("business_date"));String source=WireValues.id(key.get("source_id"),true),run=WireValues.id(key.get("run_id"),false);
        var registered=registrations.prepare(Set.of(day),Set.of(source));if(!registered.runIds().contains(run))return null;
        var reference=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(own.toJson().get("stage_result").get("plan_ref")));
        reads.execute(status->{readOwn(own,reference,cutoff);return null;});
        var partial=partial(own)?partials.capture(own.toJson(),cutoff):null;
        var dependency=FrozenSourceDayDedupDependency.create(closed.original().plan().directory().ownerId(),closed.original().plan().bundleHash(),closed.closure(),own,cutoff);
        if(expected!=null&&!expected.equals(dependency.json()))throw invalid();var archive=reads.execute(s->archives.read(day,"news_tech"));
        if(archive==null||!closed.closure().json().equals(archive.closureJson())||!closed.original().plan().bundleHash().equals(archive.bundleHash()))throw invalid();
        return new Captured(this,dependency,own,archive,registered,partial);
    }
    /** Checks additional day authority in this exact writer; it does not replace other execution proofs. */
    public FrozenSourceDayDedupDependency verify(Captured captured,Instant cutoff){
        JdbcFrozenInputs.requireTx();if(captured==null||captured.owner!=this)throw invalid();var key=captured.own.toJson().get("stage_key");
        FrozenSourceDayDedupDependency.read(captured.dependency.json(),captured.own,cutoff);
        if(!captured.archive.equals(archives.read(captured.archive.day(),"news_tech"))||!registrations.allows(captured.registered,WireValues.id(key.get("run_id"),false)))throw invalid();
        var proven=partial(captured.own)?dependencies.readPartial(captured.partial,cutoff):dependencies.read(captured.own.toJson(),cutoff);if(!captured.own.json().equals(proven.dependency().json()))throw invalid();return captured.dependency;
    }
    /** Actual read-only original lookup. A later writer still needs verify and its other qualifications. */
    public FrozenSourceDayDedupDependency verifyObserved(Captured captured,Instant cutoff){
        JdbcSourceDedupResults.requireReadOnly();if(captured==null||captured.owner!=this)throw invalid();var key=captured.own.toJson().get("stage_key");
        FrozenSourceDayDedupDependency.read(captured.dependency.json(),captured.own,cutoff);
        if(!captured.archive.equals(archives.read(captured.archive.day(),"news_tech"))||!registrations.allowsObserved(captured.registered,WireValues.id(key.get("run_id"),false)))throw invalid();
        readOwn(captured.own,FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(captured.own.toJson().get("stage_result").get("plan_ref"))),cutoff);return captured.dependency;
    }
    private void readOwn(FrozenSourceDedupDependency own,FrozenSourceStagePlan.Reference reference,Instant cutoff){
        JdbcSourceDedupResults.requireReadOnly();var original=proofs.readCommitted(reference);
        if(original==null||!original.proof().json().equals(JdbcFrozenInputs.canonical(own.toJson().get("stage_result")))||!own.observedAt().equals(availability.availableAt(original.proof(),cutoff)))throw invalid();
        var proven=partial(own)?dependencies.readPartial(own.toJson(),cutoff):dependencies.read(own.toJson(),cutoff);if(!own.json().equals(proven.dependency().json()))throw invalid();
    }
    private static FrozenSourceDedupDependency own(JsonNode profile,Instant cutoff){return "source-dedup-dependency-v2".equals(WireValues.text(profile.get("schema_version")))?FrozenSourceDedupDependency.readPartial(profile,cutoff):FrozenSourceDedupDependency.read(profile,cutoff);}
    private static boolean partial(FrozenSourceDedupDependency own){return "source-dedup-dependency-v2".equals(WireValues.text(own.toJson().get("schema_version")));}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original full news day writer permission unavailable");}
    @Override public String toString(){return "SourceDayNewsValuePermitService[redacted]";}
}
