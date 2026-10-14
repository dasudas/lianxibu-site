package com.lianxibu.site.config;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.JdbcSourceMemberStages;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.sources.SourceMemberStageService;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** A fresh mutable result adapter per original permit; shared collaborators are stateless. */
public final class ResultApplicationFactory implements AttemptOutcomeService.Applications {
    private static final String ACTOR="analysis-worker";
    private final ReportDispatchPermitService reportPermissions;private final DataSource source;private final Clock clock;
    private final SharedWriteExecutor writes;private final ContentReadService contents;
    private final GeneratedContentService generated;private final JdbcFrozenInputs inputs;
    private final FrozenContentVersionService versions;private final VersionValidityLedgerService ledger;
    private final FrozenReferenceClosure closure;
    private final Map<String,DedupResultHandler> catalogDedup;
    private final DedupResultHandler dedup;private final ValueResultHandler value;private final AdHocResultHandler adHoc;
    private final AnalysisResultDownstream analysis;private final ReportResultDownstream reports;
    private final TransactionTemplate reads;private final PlatformTransactionManager manager;private final Map<String,CatalogDayAnalysisResultPermitService> catalogPermissions;
    public ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock){
        this(source,tx,clock,null,null,false);
    }
    /** Explicit actual proof ports; construction performs no database or network work. */
    public ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock,SourceDedupDependencyService dependencies,SourceDedupPartialDependencyPermitService permits){
        this(source,tx,clock,Objects.requireNonNull(dependencies),Objects.requireNonNull(permits),true);
    }
    private ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock,SourceDedupDependencyService dependencies,SourceDedupPartialDependencyPermitService permits,boolean actual){this(source,tx,clock,dependencies,permits,actual,null);}
    public ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock,Map<String,CatalogDayAnalysisResultPermitService> catalogPermissions){this(source,tx,clock,null,null,false,Objects.requireNonNull(catalogPermissions));}
    public ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock,SourceDedupDependencyService dependencies,SourceDedupPartialDependencyPermitService permits,Map<String,CatalogDayAnalysisResultPermitService> catalogPermissions){this(source,tx,clock,Objects.requireNonNull(dependencies),Objects.requireNonNull(permits),true,Objects.requireNonNull(catalogPermissions));}
    /** Whole original day routing explicitly supplies native touched count snapshots. */
    public ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock,SourceDedupDependencyService dependencies,SourceDedupPartialDependencyPermitService permits,Map<String,CatalogDayAnalysisResultPermitService> catalogPermissions,JdbcEntityHeatSnapshots heat){this(source,tx,clock,Objects.requireNonNull(dependencies),Objects.requireNonNull(permits),true,Objects.requireNonNull(catalogPermissions),Objects.requireNonNull(heat));}
    private ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock,SourceDedupDependencyService dependencies,SourceDedupPartialDependencyPermitService permits,boolean actual,Map<String,CatalogDayAnalysisResultPermitService> catalogPermissions){this(source,tx,clock,dependencies,permits,actual,catalogPermissions,null);}
    private ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock,SourceDedupDependencyService dependencies,SourceDedupPartialDependencyPermitService permits,boolean actual,Map<String,CatalogDayAnalysisResultPermitService> catalogPermissions,JdbcEntityHeatSnapshots heat){this(source,tx,clock,dependencies,permits,actual,catalogPermissions,heat,null);}
    public ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock,SourceDedupDependencyService dependencies,SourceDedupPartialDependencyPermitService permits,Map<String,CatalogDayAnalysisResultPermitService> catalogPermissions,JdbcEntityHeatSnapshots heat,ReportDispatchPermitService reportPermissions){this(source,tx,clock,Objects.requireNonNull(dependencies),Objects.requireNonNull(permits),true,Objects.requireNonNull(catalogPermissions),Objects.requireNonNull(heat),Objects.requireNonNull(reportPermissions));}
    private ResultApplicationFactory(DataSource source,PlatformTransactionManager tx,Clock clock,SourceDedupDependencyService dependencies,SourceDedupPartialDependencyPermitService permits,boolean actual,Map<String,CatalogDayAnalysisResultPermitService> catalogPermissions,JdbcEntityHeatSnapshots heat,ReportDispatchPermitService reportPermissions){this.reportPermissions=reportPermissions;
        if(catalogPermissions!=null&&!catalogPermissions.keySet().equals(Set.of("agent","skill","workflow","model")))throw new IllegalArgumentException("complete catalog result permissions required");this.catalogPermissions=catalogPermissions==null?null:Map.copyOf(catalogPermissions);this.manager=Objects.requireNonNull(tx);
        this.source=Objects.requireNonNull(source);this.clock=Objects.requireNonNull(clock);
        writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),Objects.requireNonNull(tx));
        if(actual){reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}else reads=null;
        contents=new ContentReadService(new JdbcContentReads(source),tx,ContentReadService.denyUnverifiedReferences());
        generated=new GeneratedContentService(new JdbcGeneratedContents(source),clock);inputs=new JdbcFrozenInputs(source);
        versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(source));
        var evidence=new EvidenceValidityService(new JdbcEvidenceValidity(source));
        ledger=new VersionValidityLedgerService(new JdbcVersionValidity(source),versions,evidence,new ContentReviewWriteService(new JdbcReviewPublication(source),generated,clock),clock,ACTOR);
        closure=new FrozenReferenceClosure(new FrozenReferenceClosure.Source(){
            @Override public FrozenReferenceClosure.Evidence resolve(ContentVersionKey target){return versions.resolve(target);}
            @Override public boolean leafExists(JsonNode target){return ledger.exactVersionExists(target);}
        });
        var firstLedgers=new HashMap<String,CatalogVersionJudgmentLedger>();
        for(String category:List.of("agent","skill","workflow","model"))firstLedgers.put(category,new CatalogVersionJudgmentLedger(new JdbcCatalogVersionJudgments(source),category));
        var downstream=new MemberResultDownstream(inputs,new JdbcMemberStageResults(source),new SourceMemberStageService(new JdbcSourceMemberStages(source),clock),clock,firstLedgers);
        var entityLog=heat==null?new EntityDecisionJdbcLog(new JdbcEntityDecisions(source),ACTOR,clock):new EntityDecisionJdbcLog(new JdbcEntityDecisions(source),ACTOR,clock,heat);
        var entities=new EntityDedupService(new JdbcEntityDedup(source),writes,entityLog,clock);
        dedup=new DedupResultHandler(entities,inputs::read,downstream);
        var catalogHandlers=new HashMap<String,DedupResultHandler>();
        for(String category:List.of("agent","skill","workflow","model")){
            var batches=new CatalogVersionBatchPreflightService(source,tx,category);
            var judgments=new CatalogVersionBatchJudgmentWriter(source,batches.permits(),new EntityAnalysisStateService(new JdbcEntityAnalysisState(source)),category,clock);
            catalogHandlers.put(category+"_dedup",new DedupResultHandler(entities,inputs::read,downstream,batches,judgments));
        }
        catalogDedup=Map.copyOf(catalogHandlers);
        var values=new ValueApplicationService(new JdbcValueWrites(source));
        value=actual?new ValueResultHandler(values,inputs::read,downstream,dependencies,permits,tx):new ValueResultHandler(values,inputs::read,downstream,new SourceDedupDependencyService(new JdbcSourceStageAvailability(source)));
        adHoc=new AdHocResultHandler(inputs::read);
        analysis=new AnalysisResultDownstream(inputs,contents,new EntityAnalysisStateService(new JdbcEntityAnalysisState(source)));
        reports=new ReportResultDownstream(inputs,new JdbcReportResults(source),contents,clock);
    }
    @Override public AttemptOutcomeService.Application forAttempt(WorkerAuthorizationService.Identity identity){
        var store=new ResultJdbcStore(source,Objects.requireNonNull(identity),contents,this::valid,clock,ACTOR);
        if(reads!=null&&TransactionSynchronizationManager.isActualTransactionActive())throw new ResultApplicationService.Rejected();
        var frame=reads==null?new Frame(store.input(),false):reads.execute(s->frame(store,identity));
        Objects.requireNonNull(frame);
        var handler=reads!=null&&frame.input().stage()==GenerationFlowService.Stage.NEWS_VALUE&&!frame.applied()?value.capture(frame.input()):value;
        var service=new ResultApplicationService(writes,store,Map.of("dedup",dedup(frame.input()),"value",handler,"analysis",new CatalogDayAnalysisResultHandler(new ContentResultHandler(generated,store::context,analysis),inputs,manager,catalogPermissions),"report",new ReportProgramResultHandler(new ContentResultHandler(generated,store::context,reports),inputs,manager,reportPermissions),"ad_hoc",adHoc));
        return new AttemptOutcomeService.Application(frame.input(),service);
    }
    /** Current owner identity is recorded by the strict manual store, separate from the worker. */
    public AttemptOutcomeService.Application forManual(WorkerAuthorizationService.Identity identity,com.lianxibu.site.service.identity.IdentityService.Actor actor){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new com.lianxibu.site.service.identity.IdentityService.Rejected(com.lianxibu.site.service.identity.IdentityService.Refusal.UNAUTHENTICATED,null);
        if(!"owner".equals(actor.role()))throw new com.lianxibu.site.service.identity.IdentityService.Rejected(com.lianxibu.site.service.identity.IdentityService.Refusal.FORBIDDEN,null);
        WireValues.id(actor.userId(),false);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new ResultApplicationService.Rejected();
        var store=new ResultJdbcStore(source,Objects.requireNonNull(identity),contents,this::valid,clock,actor.userId(),true);
        var frame=reads==null?new Frame(store.input(),false):reads.execute(s->frame(store,identity));Objects.requireNonNull(frame);
        var input=frame.input();if(!input.stage().analysis()&&!input.stage().report()&&reads==null)throw new ResultApplicationService.Rejected();
        var handler=reads!=null&&input.stage()==GenerationFlowService.Stage.NEWS_VALUE&&!frame.applied()?value.capture(input):value;
        var service=new ResultApplicationService(writes,store,Map.of("dedup",manualDedup(input),"value",handler,"analysis",new CatalogDayAnalysisResultHandler(new ContentResultHandler(generated,store::context,analysis),inputs,manager,catalogPermissions),"report",new ReportProgramResultHandler(new ContentResultHandler(generated,store::context,reports),inputs,manager,reportPermissions),"ad_hoc",adHoc));
        return new AttemptOutcomeService.Application(input,service);
    }
    /** Original receipt lookup precedes the result frame and native batch preparation. */
    private ResultApplicationService.Handler manualDedup(ResultApplicationService.Input input){
        var delegate=dedup(input);if(!"2".equals(input.dedupResultVersion()))return delegate;
        return new ResultApplicationService.Handler(){
            @Override public ResultApplicationService.Handler prepareManual(ResultApplicationService.Input value){
                if(!input.equals(value)||reads==null||TransactionSynchronizationManager.isActualTransactionActive())throw new ResultApplicationService.Rejected();
                var snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                var frame=snapshot.execute(s->new JdbcOwnerRetainedResultReads(source).read(input.rootId()));
                if(frame==null||frame.identity()==null||!input.attemptId().equals(frame.identity().attemptId())||!input.inputHash().equals(frame.inputHash())||input.generation()!=frame.generation()||frame.envelope()==null)throw new ResultApplicationService.Rejected();
                return delegate.prepareForManual(input,ResultApplicationService.validate(input,frame.envelope()));
            }
            @Override public ResultApplicationService.Applied apply(ResultApplicationService.Input value,ResultApplicationService.Validated result,ResultApplicationService.Current current,boolean manual){throw new ResultApplicationService.Rejected();}
        };
    }
    private DedupResultHandler dedup(ResultApplicationService.Input input){
        if(!"2".equals(input.dedupResultVersion()))return dedup;
        var handler=catalogDedup.get(input.stage().name().toLowerCase(Locale.ROOT));if(handler==null)throw new ResultApplicationService.Rejected();return handler;
    }
    private record Frame(ResultApplicationService.Input input,boolean applied){ }
    private Frame frame(ResultJdbcStore store,WorkerAuthorizationService.Identity identity){
        var input=store.input();if(input.stage()!=GenerationFlowService.Stage.NEWS_VALUE)return new Frame(input,false);
        var root=new JdbcResultState(source).root(identity.rootId());if(!identity.inputHash().equals(root.hash()))throw new ResultApplicationService.Rejected();
        boolean applied=root.appliedAt()!=null||"APPLIED".equals(root.application());
        if(applied){
            if(root.appliedAt()==null||!"APPLIED".equals(root.application())||!"succeeded".equals(root.state())||root.resultHash()==null||store.retained(root.id())==null)throw new ResultApplicationService.Rejected();
            // The original receipt and retained input grant replay only. A later natural-key
            // generation must not require a new P1 permission for this durable application.
        }
        return new Frame(input,applied);
    }
    private boolean valid(JsonNode target){
        if(!ledger.exactVersionExists(target)||!ledger.validity(target).effectiveValid())return false;
        if(!"content_version".equals(target.path("kind").asText()))return true;
        var version=ContentVersionKey.fromJson(target);var proof=versions.find(version).orElseThrow();
        return closure.expand(version,proof.context()).targets().stream().allMatch(ref->ledger.validity(ref).effectiveValid());
    }
    ResultJdbcStore.ReferenceProof referenceProof(){return this::valid;}
}
