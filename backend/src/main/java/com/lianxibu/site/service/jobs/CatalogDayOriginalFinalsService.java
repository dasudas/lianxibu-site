package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** All included original catalog finals in one native read snapshot. This capability grants no writer or model authority. */
public final class CatalogDayOriginalFinalsService {
    public static final long MAX_PROOF_BYTES=32L*1024*1024;
    public static final class Captured {
        private final Object issuer;private final CatalogSourceDayDedupBarrierService.Closed closed;private final JdbcSourceDayDedupClosures.Archive archive;
        private final List<CatalogSourceFinalizationService.Committed> sources;
        private Captured(Object issuer,CatalogSourceDayDedupBarrierService.Closed closed,JdbcSourceDayDedupClosures.Archive archive,List<CatalogSourceFinalizationService.Committed> sources){this.issuer=issuer;this.closed=closed;this.archive=archive;this.sources=List.copyOf(sources);}
        public String category(){return closed.closure().cohort().category();}public LocalDate day(){return closed.closure().cohort().day();}
        public Instant cutoff(){return closed.closure().cutoff();}public String closureHash(){return closed.closure().hash();}
        public CatalogSourceDayDedupBarrierService.Closed closed(){return closed;}public List<CatalogSourceFinalizationService.Committed> sources(){return sources;}
        @Override public String toString(){return "OriginalCatalogDayFinals[redacted]";}
    }
    private final Object issuer=new Object();private final String category;private final CatalogSourceDayDedupBarrierService barriers;
    private final JdbcSourceDayDedupClosures closures;private final FrozenSourceStagePlanService plans;private final CatalogSourceFinalizationService finals;
    private final CatalogSourceFinalAvailabilityService availability;private final TransactionTemplate reads;
    public CatalogDayOriginalFinalsService(CatalogSourceDayDedupBarrierService barriers,JdbcSourceDayDedupClosures closures,FrozenSourceStagePlanService plans,
            CatalogSourceFinalizationService finals,CatalogSourceFinalAvailabilityService availability,PlatformTransactionManager manager,String category){
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};this.barriers=Objects.requireNonNull(barriers);this.closures=Objects.requireNonNull(closures);this.plans=Objects.requireNonNull(plans);this.finals=Objects.requireNonNull(finals);this.availability=Objects.requireNonNull(availability);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Captured capture(LocalDate day){outside();var closed=barriers.close(day);return closed==null?null:read(closed);}
    public Captured captureOriginal(LocalDate day){outside();var closed=barriers.find(day);return closed==null?null:read(closed);}
    private Captured read(CatalogSourceDayDedupBarrierService.Closed closed){return reads.execute(s->{var archive=archive(closed);return new Captured(issuer,closed,archive,sources(closed));});}
    /** Borrows an exact readonly RR transaction to prove the complete original snapshot again. */
    public Captured verifyObserved(Captured captured){
        readonly();if(captured==null||captured.issuer!=issuer||!category.equals(captured.category())||!captured.archive.equals(archive(captured.closed)))throw invalid();
        var original=sources(captured.closed);if(original.size()!=captured.sources.size())throw invalid();for(int i=0;i<original.size();i++){var a=original.get(i);var b=captured.sources.get(i);if(!a.result().json().equals(b.result().json())||!a.receipt().json().equals(b.receipt().json())||!a.minimumObservationTime().equals(b.minimumObservationTime()))throw invalid();}return captured;
    }
    private JdbcSourceDayDedupClosures.Archive archive(CatalogSourceDayDedupBarrierService.Closed closed){
        readonly();if(closed==null||!category.equals(closed.closure().cohort().category()))throw invalid();var original=closed.original();var closure=closed.closure();var archive=closures.read(closure.cohort().day(),category);
        if(archive==null||!archive.ownerId().equals(original.plan().directory().ownerId())||!archive.bundleHash().equals(original.plan().bundleHash())||!archive.cohortHash().equals(closure.cohort().hash())||!archive.closureHash().equals(closure.hash())||!archive.closureJson().equals(closure.json()))throw invalid();return archive;
    }
    private List<CatalogSourceFinalizationService.Committed> sources(CatalogSourceDayDedupBarrierService.Closed closed){
        readonly();var closure=closed.closure();var output=new ArrayList<CatalogSourceFinalizationService.Committed>();long bytes=0;
        for(var entry:closure.entries())if(entry.decision()==SourceDedupBarrier.Decision.INCLUDED){
            var fact=entry.progress();if(fact==null||!category.equals(fact.category())||!closure.cohort().day().equals(fact.day())||!entry.sourceId().equals(fact.sourceId())||fact.runGeneration()!=1||fact.availableAt().isAfter(closure.cutoff()))throw invalid();
            var address=CollectionIntentIdentity.create(entry.sourceId(),"daily",closure.cohort().day(),1,category);var stage=GenerationFlowService.Stage.valueOf(category.toUpperCase(Locale.ROOT)+"_DEDUP");
            var reference=plans.findImmutableReference(address.id(),address.logicalKey(),1,stage,closure.cohort().day());if(reference==null)throw invalid();var proof=finals.readCommitted(reference);if(proof==null)throw invalid();var receipt=proof.receipt();
            if(!category.equals(receipt.category())||!entry.sourceId().equals(receipt.sourceId())||!fact.runId().equals(receipt.runId())||!fact.day().equals(receipt.businessDate())||receipt.collectionGeneration()!=1||!fact.proofHash().equals(proof.result().hash())||!fact.availableAt().equals(availability.availableAt(proof,closure.observedAt())))throw invalid();
            var state="partial".equals(proof.result().coverageState())?SourceDedupBarrier.State.PARTIAL:WireValues.nonnegativeInteger(CanonicalJson.parse(proof.result().json()).get("eligible_count"))==0?SourceDedupBarrier.State.EMPTY_SUCCESS:SourceDedupBarrier.State.COMPLETE;if(fact.state()!=state)throw invalid();
            bytes+=proof.result().json().getBytes(StandardCharsets.UTF_8).length+receipt.json().getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_PROOF_BYTES)throw new GenerationFlowService.CapacityGuarded();output.add(proof);
        }
        return List.copyOf(output);
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("complete original catalog day finals unavailable");}
}
