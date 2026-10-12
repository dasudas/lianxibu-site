package com.lianxibu.site.config;

import com.lianxibu.site.repository.articles.JdbcNewsCandidateHistory;
import com.lianxibu.site.repository.articles.JdbcNewsDedupEligibility;
import com.lianxibu.site.repository.articles.JdbcNewsValueEntities;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.repository.jobs.JdbcFrozenSourcePlans;
import com.lianxibu.site.repository.jobs.JdbcFrozenSourceUniverses;
import com.lianxibu.site.repository.jobs.JdbcSourceStageBirth;
import com.lianxibu.site.repository.jobs.JdbcSourceDedupResults;
import com.lianxibu.site.repository.jobs.JdbcSourceStageAvailability;
import com.lianxibu.site.repository.jobs.JdbcSourceValueStageAvailability;
import com.lianxibu.site.repository.jobs.JdbcSourceValueFinalBlocks;
import com.lianxibu.site.repository.jobs.JdbcSourceValueFinalReceipts;
import com.lianxibu.site.repository.jobs.JdbcSourceValueFinalAvailability;
import com.lianxibu.site.repository.jobs.JdbcSourceValueIntents;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.JdbcCollectionHandoff;
import com.lianxibu.site.repository.sources.JdbcSourceRunUniverse;
import com.lianxibu.site.repository.sources.JdbcSourceRunAnalysis;
import com.lianxibu.site.repository.sources.JdbcSourceValueIntentLinks;
import com.lianxibu.site.service.articles.NewsCandidateHistoryService;
import com.lianxibu.site.service.articles.NewsDedupEligibilityService;
import com.lianxibu.site.service.articles.NewsValueEligibilityService;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import com.lianxibu.site.service.sources.SourceRunUniverseService;
import com.lianxibu.site.service.sources.SourceRunAnalysisService;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual preparation providers only. A separate parent owns transactions and scheduling. */
@Configuration(proxyBeanMethods=false)
@Import({SourceAnalysisProofConfiguration.class,SourceDayNewsValuePermitConfiguration.class})
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled",
        "site.execution.enabled","site.collector.enabled","site.execution.preparation.enabled"},havingValue="true")
public class NewsSourcePreparationConfiguration {
    private record Originals(CollectionHandoffProofService collections,FrozenSourceUniverseService universes,FrozenSourceStagePlanService plans) { }

    @Bean("newsSourcePlanPreparation") public NewsSourcePlanPreparationService newsSourcePlanPreparationProvider(DataSource source,SettingsUpdateService settings,OperationalInputGate gate,SourceAnalysisProofGraph graph) {
        Clock clock=Clock.systemUTC();
        var eligibility=new NewsDedupEligibilityService(new JdbcNewsDedupEligibility(source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source)));
        return new NewsSourcePlanPreparationService(new JdbcFrozenSourcePlans(source),graph.universes(),graph.plans(),graph.collections(),eligibility,
                new NewsCandidateHistoryService(new JdbcNewsCandidateHistory(source),clock),mappedSettings(settings),gate::preparationAllowed);
    }

    /** Existing explicit full preparation entry for manual assembly. */
    public NewsSourcePlanPreparationService newsSourcePlanPreparation(DataSource source,SettingsUpdateService settings,OperationalInputGate gate) {
        Clock clock=Clock.systemUTC();var original=originals(source,clock);
        var eligibility=new NewsDedupEligibilityService(new JdbcNewsDedupEligibility(source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source)));
        return new NewsSourcePlanPreparationService(new JdbcFrozenSourcePlans(source),original.universes(),original.plans(),original.collections(),eligibility,
                new NewsCandidateHistoryService(new JdbcNewsCandidateHistory(source),clock),mappedSettings(settings),gate::preparationAllowed);
    }

    @Bean public NewsValueSourcePlanPreparationService newsValueSourcePlanPreparation(DataSource source,SettingsUpdateService settings,OperationalInputGate gate,SourceAnalysisProofGraph graph,ObjectProvider<SourceDayNewsValuePermitService> days,Environment flags) {
        Clock clock=Clock.systemUTC();
        var eligibility=new NewsValueEligibilityService(new JdbcNewsDedupEligibility(source),new JdbcNewsValueEntities(source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source)),clock);
        return new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(source),new JdbcFrozenSourceUniverses(source),graph.universes(),graph.plans(),graph.collections(),eligibility,
                graph.dependencies(),mappedSettings(settings),clock,gate::preparationAllowed,"2",dayPermits(days,flags));
    }
    @Bean public SourceDedupStageProofService sourceDedupStageProof(SourceAnalysisProofGraph graph){return graph.dedupProofs();}
    @Bean public SourceDedupStageAvailabilityService sourceDedupStageAvailability(SourceAnalysisProofGraph graph){return graph.dedupObserver();}
    @Bean public SourceValueIntentReceiptService sourceValueIntentReceipts(SourceAnalysisProofGraph graph){return graph.handoffReceipts();}
    @Bean public SourceValueIntentService sourceValueIntents(SourceAnalysisProofGraph graph){return graph.handoffs();}
    @Bean public SourceValueStageProofService sourceValueStageProof(SourceAnalysisProofGraph graph){return graph.valueProofs();}
    @Bean public SourceValueStageAvailabilityService sourceValueStageAvailability(SourceAnalysisProofGraph graph){return graph.valueObserver();}
    @Bean public SourceValueFinalProofService sourceValueFinalProof(SourceAnalysisProofGraph graph){return graph.finalProofs();}
    @Bean public SourceValueFinalReceiptService sourceValueFinalReceipts(SourceAnalysisProofGraph graph){return graph.finalReceipts();}
    @Bean public SourceRunAnalysisService sourceRunAnalysis(DataSource source,SourceValueFinalReceiptService receipts){return new SourceRunAnalysisService(new JdbcSourceRunAnalysis(source),receipts::prior);}
    @Bean public SourceValueFinalArchiveService sourceValueFinalArchive(SourceAnalysisProofGraph graph){return graph.finalArchives();}
    @Bean public SourceValueFinalizationService sourceValueFinalization(SourceAnalysisProofGraph graph){return graph.finalization();}
    @Bean public SourceValueFinalAvailabilityService sourceValueFinalAvailability(SourceAnalysisProofGraph graph){return graph.finalObserver();}
    @Bean("sourceStagePreparation") public SourceStagePreparationService sourceStagePreparationProvider(SourceAnalysisProofGraph graph){return graph.parts();}

    /** Existing explicit full preparation entry for manual assembly. */
    public SourceStagePreparationService sourceStagePreparation(DataSource source) {
        Clock clock=Clock.systemUTC();var original=originals(source,clock);
        var births=new SourceStageBirthProofService(new JdbcSourceStageBirth(source),new JdbcFrozenSourcePlans(source),original.plans());
        return new SourceStagePreparationService(source,original.plans(),original.universes(),original.collections()::read,clock,"analysis-worker",births::read);
    }
    @Bean public SourcePlanPreparationCoordinator sourcePlanPreparationCoordinator(DataSource source,PlatformTransactionManager tx,NewsSourcePlanPreparationService dedup,NewsValueSourcePlanPreparationService value,SourceStagePreparationService parts,SourceValueIntentService handoffs,SourceDedupStageProofService proofs,OperationalInputGate gate,SourceAnalysisProofGraph graph,ObjectProvider<SourceDayNewsValuePermitService> days,Environment flags) {
        return new SourcePlanPreparationCoordinator(dedup,value,parts,handoffs,proofs,new JdbcFrozenSourcePlans(source),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),tx,Clock.systemUTC(),gate::preparationAllowed,graph.dedupPermits(),dayPermits(days,flags));
    }
    @Bean public SourceAnalysisParentStepService sourceAnalysisParentStep(DataSource source,PlatformTransactionManager tx,SourcePlanPreparationCoordinator preparation,SourceAnalysisProofGraph graph){
        return parentStep(source,tx,Clock.systemUTC(),preparation,graph);
    }
    /** The same actual graph supplies preparation, resolution and both partial seals. */
    public static SourceAnalysisParentStepService parentStep(DataSource source,PlatformTransactionManager tx,Clock clock,SourcePlanPreparationCoordinator preparation,SourceAnalysisProofGraph graph){
        Objects.requireNonNull(source);Objects.requireNonNull(tx);Objects.requireNonNull(clock);Objects.requireNonNull(preparation);Objects.requireNonNull(graph);
        var origins=new SourceIntentOriginService(new com.lianxibu.site.repository.jobs.JdbcSourceIntentScan(source),graph.handoffReceipts());
        return new SourceAnalysisParentStepService(origins,new JdbcFrozenSourcePlans(source),graph.plans(),graph.parts(),preparation,graph.resolutions(),graph.handoffs(),graph.finalization(),graph.finalObserver(),tx,clock,graph.dedupSeals(),graph.valueSeals());
    }

    private static SourceDayNewsValuePermitService dayPermits(ObjectProvider<SourceDayNewsValuePermitService> days,Environment flags){return flags.getProperty("site.sources.day-roster.enabled",Boolean.class,false)?days.getObject():null;}
    private static Originals originals(DataSource source,Clock clock) {
        var terminal=new CollectionHandoffService(new JdbcCollectionHandoff(source),facts->{throw new IllegalStateException("read-only collection proof provider");});
        var collections=new CollectionHandoffProofService(source,terminal);
        var universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(source),new SourceRunUniverseService(new JdbcSourceRunUniverse(source),terminal),clock);
        return new Originals(collections,universes,new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(source),universes,clock,"analysis-worker"));
    }
    private static GenerationFlowService.SettingsSnapshotSource mappedSettings(SettingsUpdateService settings) {
        return ()->{var frozen=settings.freezeForNewInput();return new FrozenGenerationSettings(frozen.settingsRevision(),frozen.followKeywords(),frozen.blockedKeywords());};
    }
}
