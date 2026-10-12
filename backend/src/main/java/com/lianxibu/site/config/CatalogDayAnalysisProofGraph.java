package com.lianxibu.site.config;

import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.CatalogAnalysisContentBaselineService;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual original day and entity analysis providers. Construction does not access the database. */
public final class CatalogDayAnalysisProofGraph {
    private final String category;
    private final FrozenSourceStagePlanService plans;
    private final CatalogSourceDayDedupBarrierService barrier;
    private final CatalogDayEntityEvidenceService evidence;
    private final CatalogDayAnalysisCommitPermitService originals;
    private final CatalogDayAnalysisEligibilityService eligibility;
    private final CatalogDayAnalysisReservationLedger reservations;
    private final CatalogDayAnalysisGenerationService allocation;
    private final CatalogDayAnalysisInputPermitService inputs;
    private final CatalogAnalysisPreparedInputService prepared;
    private final CatalogDayAnalysisPreparationService preparation;
    private final CatalogDayAnalysisDispatchPermitService dispatch;
    private final CatalogDayAnalysisResultPermitService result;
    private final CatalogDayAnalysisPreparationCycle cycle;

    public CatalogDayAnalysisProofGraph(DataSource source,PlatformTransactionManager tx,Clock clock,
            SourceDayCohortSealService cohorts,SourceDayRunRegistration.Api runs,CatalogSourceProofGraph sources,
            GenerationFlowService.SettingsSnapshotSource settings,BooleanSupplier allowed,String category) {
        Objects.requireNonNull(source);Objects.requireNonNull(tx);Objects.requireNonNull(clock);
        Objects.requireNonNull(cohorts);Objects.requireNonNull(runs);Objects.requireNonNull(sources);
        Objects.requireNonNull(settings);Objects.requireNonNull(allowed);
        plans=sources.plans();
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw new IllegalArgumentException("unsupported catalog category");};
        if(!category.equals(sources.category()))throw new IllegalArgumentException("catalog source category mismatch");
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
        var closures=new JdbcSourceDayDedupClosures(source);
        var progress=new CatalogSourceDayDedupProgressService(runs,sources.plans(),sources.finalizer(),sources.observer(),tx,category);
        barrier=new CatalogSourceDayDedupBarrierService(cohorts,progress,closures,writes,tx,clock,category);
        var finals=new CatalogDayOriginalFinalsService(barrier,closures,sources.plans(),sources.finalizer(),sources.observer(),tx,category);
        evidence=new CatalogDayEntityEvidenceService(finals,sources.plans(),tx,category);
        originals=new CatalogDayAnalysisCommitPermitService(evidence,sources.permits(),closures,tx,category);
        var headers=new CatalogEntityAnalysisHeaderService(source,tx,category);
        var members=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source));
        var current=new EntityInputEvidenceService(new JdbcEntityDedup(source),members);
        var judgments=new CatalogVersionJudgmentLedger(new JdbcCatalogVersionJudgments(source),category);
        var owners=new JdbcFrozenSourcePlans(source);var records=new JdbcJobRecords(source);
        eligibility=new CatalogDayAnalysisEligibilityService(originals,headers,current,sources.plans(),judgments,owners,records,tx,category);
        reservations=new CatalogDayAnalysisReservationLedger(new JdbcCatalogDayAnalysisReservations(source),category);
        allocation=new CatalogDayAnalysisGenerationService(eligibility,reservations,new EntityAnalysisStateService(new JdbcEntityAnalysisState(source)),records,writes,clock,category);
        var contents=new CatalogAnalysisContentBaselineService(source,tx,category);
        var previous=new CatalogPreviousAnalysisService(source,tx);
        var previousJobs=new JdbcCatalogVersionJobFacts(source,category);
        inputs=new CatalogDayAnalysisInputPermitService(originals,reservations,headers,current,sources.plans(),judgments,owners,records,contents,previous,previousJobs,tx,category);
        prepared=new CatalogAnalysisPreparedInputService(source,tx,clock,category);
        var metadata=new AnalysisPublicMetadataEnricher(current,new ArticlePublicEvidenceService(new JdbcArticlePublicEvidence(source),members));
        preparation=new CatalogDayAnalysisPreparationService(source,prepared,inputs,writes,settings,metadata,new GenerationRequestBuilder(),clock,allowed,category);
        dispatch=new CatalogDayAnalysisDispatchPermitService(source,prepared,originals,headers,current,contents,previous,previousJobs,tx,category);
        result=new CatalogDayAnalysisResultPermitService(dispatch,category);
        cycle=new CatalogDayAnalysisPreparationCycle(new JdbcCatalogAnalysisDays(source),evidence,eligibility,reservations,allocation,prepared,preparation,tx,clock,allowed,category);
    }
    public String category(){return category;}
    public FrozenSourceStagePlanService plans(){return plans;}
    public CatalogSourceDayDedupBarrierService barrier(){return barrier;}
    public CatalogDayEntityEvidenceService evidence(){return evidence;}
    public CatalogDayAnalysisCommitPermitService originals(){return originals;}
    public CatalogDayAnalysisEligibilityService eligibility(){return eligibility;}
    public CatalogDayAnalysisReservationLedger reservations(){return reservations;}
    public CatalogDayAnalysisGenerationService allocation(){return allocation;}
    public CatalogDayAnalysisInputPermitService inputs(){return inputs;}
    public CatalogAnalysisPreparedInputService prepared(){return prepared;}
    public CatalogDayAnalysisPreparationService preparation(){return preparation;}
    public CatalogDayAnalysisDispatchPermitService dispatch(){return dispatch;}
    public CatalogDayAnalysisResultPermitService result(){return result;}
    public CatalogDayAnalysisPreparationCycle cycle(){return cycle;}
    @Override public String toString(){return "CatalogDayAnalysisProofGraph[redacted]";}
}
