package com.lianxibu.site.config;

import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.sources.*;
import java.time.Clock;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** Lazy actual providers for one closed catalog category. No constructor reads or writes. */
public final class CatalogSourceProofGraph {
    private final String category;
    private final CollectionHandoffProofService collections;
    private final FrozenSourceUniverseService universes;
    private final FrozenSourceStagePlanService plans;
    private final SourceStageBirthProofService births;
    private final SourceStagePreparationService parts;
    private final SourceStageResolutionService resolutions;
    private final SourceDedupPartialSealService partials;
    private final SourceDedupStageProofService whole;
    private final CatalogSourceFinalProofService finals;
    private final CatalogSourceFinalArchiveService archives;
    private final CatalogSourceFinalReceiptService receipts;
    private final CatalogSourceFinalCommitPermitService permits;
    private final CatalogSourceFinalizationService finalizer;
    private final CatalogSourceFinalAvailabilityService observer;
    private final CatalogSourcePlanPreparationService preparation;
    private final CatalogSourcePlanPreparationCoordinator coordinator;
    private final CatalogSourceIntentOriginService origins;
    private final CatalogSourceParentStepService parent;
    private final CatalogSourcePreparationCycle cycle;

    public CatalogSourceProofGraph(DataSource source,PlatformTransactionManager tx,Clock clock,
            GenerationFlowService.SettingsSnapshotSource settings,BooleanSupplier allowed,String category) {
        Objects.requireNonNull(source);Objects.requireNonNull(tx);Objects.requireNonNull(clock);
        Objects.requireNonNull(settings);Objects.requireNonNull(allowed);
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw new IllegalArgumentException("unsupported catalog category");};
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
        var terminal=new CollectionHandoffService(new JdbcCollectionHandoff(source),facts->{throw new IllegalStateException("read-only collection proof provider");});
        collections=new CollectionHandoffProofService(source,terminal);
        universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(source),new SourceRunUniverseService(new JdbcSourceRunUniverse(source),terminal),clock);
        var parents=new JdbcFrozenSourcePlans(source);
        plans=new FrozenSourceStagePlanService(parents,universes,clock,"analysis-worker");
        births=new SourceStageBirthProofService(new JdbcSourceStageBirth(source),parents,plans);
        // Catalog dedup has no news-value dependency or partial-permit cycle.
        var dependencies=new SourceDedupDependencyService(new JdbcSourceStageAvailability(source));
        parts=new SourceStagePreparationService(source,plans,universes,collections::read,clock,"analysis-worker",births::read,dependencies);
        var results=new JdbcSourceDedupResults(source);var roots=new JdbcSourceStageRoots(source);
        var fragments=new SourceDedupResultProofService(results,plans,universes);
        resolutions=new SourceStageResolutionService(roots,plans,universes,parts,new JdbcFrozenInputs(source),results,fragments,new SourceValueResultProofService(results,plans,universes,dependencies),dependencies);
        var partialArchives=new SourceDedupPartialArchiveService(resolutions,new JdbcSourceDedupPartialBlocks(source),writes,tx,clock);
        partials=new SourceDedupPartialSealService(resolutions,partialArchives,new JdbcSourceDedupPartialReceipts(source),roots,writes,tx,clock);
        whole=new SourceDedupStageProofService(plans,universes,fragments,partials);
        var scan=new JdbcCatalogSourceIntentScan(source,category);
        origins=new CatalogSourceIntentOriginService(scan,category);
        finals=new CatalogSourceFinalProofService(whole,plans,universes,origins,category);
        archives=new CatalogSourceFinalArchiveService(finals,new JdbcCatalogSourceFinalBlocks(source,category),writes,tx,clock);
        receipts=new CatalogSourceFinalReceiptService(new JdbcCatalogSourceFinalReceipts(source),category);
        permits=new CatalogSourceFinalCommitPermitService(finals,archives,plans,resolutions,new JdbcCatalogSourceFinalCommitFacts(source,category),tx,category);
        var summaries=new SourceRunAnalysisService(new JdbcSourceRunAnalysis(source),receipts::prior,category);
        finalizer=new CatalogSourceFinalizationService(finals,archives,plans,universes,receipts,summaries,writes,permits,tx,clock,category);
        observer=new CatalogSourceFinalAvailabilityService(finalizer,new JdbcCatalogSourceFinalAvailability(source),writes,tx,clock);
        var evidence=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source));
        var eligibility=new CatalogDedupEligibilityService(new JdbcNewsDedupEligibility(source),evidence);
        var history=new CatalogCandidateHistoryService(source,category,clock);
        preparation=new CatalogSourcePlanPreparationService(parents,universes,plans,collections,eligibility,history::capture,category,settings,allowed,new CatalogSourceVersionPlanPermitService(source,tx,category));
        coordinator=new CatalogSourcePlanPreparationCoordinator(preparation,parts,parents,writes,category,allowed);
        parent=new CatalogSourceParentStepService(origins,parents,plans,parts,coordinator,resolutions,partials,finalizer,observer,tx,clock,category);
        cycle=new CatalogSourcePreparationCycle(scan,parent,tx);
    }
    public String category(){return category;}
    public CollectionHandoffProofService collections(){return collections;}
    public FrozenSourceUniverseService universes(){return universes;}
    public FrozenSourceStagePlanService plans(){return plans;}
    public SourceStageBirthProofService births(){return births;}
    public SourceStagePreparationService parts(){return parts;}
    public SourceStageResolutionService resolutions(){return resolutions;}
    public SourceDedupPartialSealService partials(){return partials;}
    public SourceDedupStageProofService whole(){return whole;}
    public CatalogSourceFinalProofService finals(){return finals;}
    public CatalogSourceFinalArchiveService archives(){return archives;}
    public CatalogSourceFinalReceiptService receipts(){return receipts;}
    public CatalogSourceFinalCommitPermitService permits(){return permits;}
    public CatalogSourceFinalizationService finalizer(){return finalizer;}
    public CatalogSourceFinalAvailabilityService observer(){return observer;}
    public CatalogSourcePlanPreparationService preparation(){return preparation;}
    public CatalogSourcePlanPreparationCoordinator coordinator(){return coordinator;}
    public CatalogSourceIntentOriginService origins(){return origins;}
    public CatalogSourceParentStepService parent(){return parent;}
    public CatalogSourcePreparationCycle cycle(){return cycle;}
    @Override public String toString(){return "CatalogSourceProofGraph[redacted]";}
}
