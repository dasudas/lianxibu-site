package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.sources.*;
import java.time.Clock;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** Lazy assembly of original source proofs. The P1 base graph prevents a partial-permit cycle. */
public final class SourceAnalysisProofGraph {
    private final CollectionHandoffProofService collections;
    private final FrozenSourceUniverseService universes;
    private final FrozenSourceStagePlanService plans;
    private final SourceStageBirthProofService births;
    private final SourceStagePreparationService parts;
    private final SourceStageResolutionService resolutions;
    private final SourceDedupPartialSealService dedupSeals;
    private final SourceDedupStageProofService dedupProofs;
    private final SourceDedupStageAvailabilityService dedupObserver;
    private final SourceDedupPartialDependencyService dedupPartials;
    private final SourceDedupPartialDependencyPermitService dedupPermits;
    private final SourceDedupDependencyService dependencies;
    private final SourceValuePartialSealService valueSeals;
    private final SourceValueStageProofService valueProofs;
    private final SourceValueStageAvailabilityService valueObserver;
    private final SourceValueIntentReceiptService handoffReceipts;
    private final SourceValueIntentService handoffs;
    private final SourceValueFinalProofService finalProofs;
    private final SourceValueFinalArchiveService finalArchives;
    private final SourceValueFinalReceiptService finalReceipts;
    private final SourceValuePartialCommitPermitService finalPermits;
    private final SourceValueFinalizationService finalization;
    private final SourceValueFinalAvailabilityService finalObserver;

    public SourceAnalysisProofGraph(DataSource source,PlatformTransactionManager tx,Clock clock,BooleanSupplier preparationAllowed){
        Objects.requireNonNull(source);Objects.requireNonNull(tx);Objects.requireNonNull(clock);Objects.requireNonNull(preparationAllowed);
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
        var terminal=new CollectionHandoffService(new JdbcCollectionHandoff(source),facts->{throw new IllegalStateException("read-only collection proof provider");});
        collections=new CollectionHandoffProofService(source,terminal);
        universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(source),new SourceRunUniverseService(new JdbcSourceRunUniverse(source),terminal),clock);
        var parentRows=new JdbcFrozenSourcePlans(source);
        plans=new FrozenSourceStagePlanService(parentRows,universes,clock,"analysis-worker");
        births=new SourceStageBirthProofService(new JdbcSourceStageBirth(source),parentRows,plans);
        var inputs=new JdbcFrozenInputs(source);var results=new JdbcSourceDedupResults(source);var roots=new JdbcSourceStageRoots(source);
        var observations=new JdbcSourceStageAvailability(source);
        var baseDependencies=new SourceDedupDependencyService(observations);
        var baseParts=new SourceStagePreparationService(source,plans,universes,collections::read,clock,"analysis-worker",births::read,baseDependencies);
        var dedupFragments=new SourceDedupResultProofService(results,plans,universes);
        var baseValueFragments=new SourceValueResultProofService(results,plans,universes,baseDependencies);
        var p1Resolutions=new SourceStageResolutionService(roots,plans,universes,baseParts,inputs,results,dedupFragments,baseValueFragments,baseDependencies);
        var p1Blocks=new JdbcSourceDedupPartialBlocks(source);var p1Receipts=new JdbcSourceDedupPartialReceipts(source);
        var p1Archives=new SourceDedupPartialArchiveService(p1Resolutions,p1Blocks,writes,tx,clock);
        dedupSeals=new SourceDedupPartialSealService(p1Resolutions,p1Archives,p1Receipts,roots,writes,tx,clock);
        dedupProofs=new SourceDedupStageProofService(plans,universes,dedupFragments,dedupSeals);
        dedupObserver=new SourceDedupStageAvailabilityService(dedupProofs,observations,writes,tx,clock);
        dedupPartials=new SourceDedupPartialDependencyService(dedupSeals,observations);
        dedupPermits=new SourceDedupPartialDependencyPermitService(dedupPartials,p1Resolutions,p1Blocks,p1Receipts,roots,observations,tx);
        dependencies=new SourceDedupDependencyService(observations,dedupPartials,dedupPermits);
        parts=new SourceStagePreparationService(source,plans,universes,collections::read,clock,"analysis-worker",births::read,dependencies);
        var valueFragments=new SourceValueResultProofService(results,plans,universes,dependencies);
        resolutions=new SourceStageResolutionService(roots,plans,universes,parts,inputs,results,dedupFragments,valueFragments,dependencies);
        var p2Blocks=new JdbcSourceValuePartialBlocks(source);var p2Receipts=new JdbcSourceValuePartialReceipts(source);
        var p2Archives=new SourceValuePartialArchiveService(resolutions,p2Blocks,writes,tx,clock);
        valueSeals=new SourceValuePartialSealService(resolutions,p2Archives,p2Receipts,roots,writes,tx,clock,dedupPermits);
        valueProofs=new SourceValueStageProofService(plans,universes,valueFragments,dependencies,valueSeals);
        valueObserver=new SourceValueStageAvailabilityService(valueProofs,new JdbcSourceValueStageAvailability(source),writes,tx,clock);
        handoffReceipts=new SourceValueIntentReceiptService(new JdbcSourceValueIntents(source),observations);
        handoffs=new SourceValueIntentService(dedupProofs,dedupObserver,plans,universes,handoffReceipts,collections,new JdbcSourceValueIntents(source),links(source),writes,tx,clock,preparationAllowed,dedupPermits);
        finalProofs=new SourceValueFinalProofService(valueProofs,plans,universes);
        finalArchives=new SourceValueFinalArchiveService(finalProofs,new JdbcSourceValueFinalBlocks(source),writes,tx,clock);
        finalReceipts=new SourceValueFinalReceiptService(new JdbcSourceValueFinalReceipts(source));
        finalPermits=new SourceValuePartialCommitPermitService(valueSeals,resolutions,p2Blocks,p2Receipts,roots,dedupPermits,tx);
        var summaries=new SourceRunAnalysisService(new JdbcSourceRunAnalysis(source),finalReceipts::prior);
        finalization=new SourceValueFinalizationService(finalProofs,finalArchives,plans,universes,finalReceipts,summaries,writes,tx,clock,finalPermits);
        finalObserver=new SourceValueFinalAvailabilityService(finalization,new JdbcSourceValueFinalAvailability(source),writes,tx,clock);
    }
    private static SourceValueIntentService.SourceLinks links(DataSource source){
        var rows=new JdbcSourceValueIntentLinks(source);
        return new SourceValueIntentService.SourceLinks(){
            public SourceValueIntentService.Header read(String run){return header(rows.read(run));}
            public SourceValueIntentService.Header locked(String run){return header(rows.locked(run));}
            public long link(String run,String p1,String p2){return rows.link(run,p1,p2);}
            private SourceValueIntentService.Header header(JdbcSourceValueIntentLinks.Header h){return new SourceValueIntentService.Header(h.runId(),h.sourceId(),h.sourceIdentity(),h.category(),h.kind(),h.day(),h.generation(),h.manifestHash(),h.expected(),h.accepted(),h.excluded(),h.pending(),h.activation(),h.delivery(),h.revision(),h.refs());}
        };
    }
    public CollectionHandoffProofService collections(){return collections;}
    public FrozenSourceUniverseService universes(){return universes;}
    public FrozenSourceStagePlanService plans(){return plans;}
    public SourceStageBirthProofService births(){return births;}
    public SourceStagePreparationService parts(){return parts;}
    public SourceStageResolutionService resolutions(){return resolutions;}
    public SourceDedupPartialSealService dedupSeals(){return dedupSeals;}
    public SourceDedupStageProofService dedupProofs(){return dedupProofs;}
    public SourceDedupStageAvailabilityService dedupObserver(){return dedupObserver;}
    public SourceDedupPartialDependencyService dedupPartials(){return dedupPartials;}
    public SourceDedupPartialDependencyPermitService dedupPermits(){return dedupPermits;}
    public SourceDedupDependencyService dependencies(){return dependencies;}
    public SourceValuePartialSealService valueSeals(){return valueSeals;}
    public SourceValueStageProofService valueProofs(){return valueProofs;}
    public SourceValueStageAvailabilityService valueObserver(){return valueObserver;}
    public SourceValueIntentReceiptService handoffReceipts(){return handoffReceipts;}
    public SourceValueIntentService handoffs(){return handoffs;}
    public SourceValueFinalProofService finalProofs(){return finalProofs;}
    public SourceValueFinalArchiveService finalArchives(){return finalArchives;}
    public SourceValueFinalReceiptService finalReceipts(){return finalReceipts;}
    public SourceValuePartialCommitPermitService finalPermits(){return finalPermits;}
    public SourceValueFinalizationService finalization(){return finalization;}
    public SourceValueFinalAvailabilityService finalObserver(){return finalObserver;}
}
