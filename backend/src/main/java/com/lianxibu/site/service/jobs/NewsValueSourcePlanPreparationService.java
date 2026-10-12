package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.NewsValueEligibilityService;
import java.time.Clock;
import java.util.*;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Archives one complete P2 plan using the observed original P1 and current qualification. */
public final class NewsValueSourcePlanPreparationService {
    private final JdbcFrozenSourcePlans storage;
    private final JdbcFrozenSourceUniverses universeStorage;
    private final FrozenSourceUniverseService originals;
    private final FrozenSourceStagePlanService plans;
    private final CollectionHandoffProofService collections;
    private final NewsValueEligibilityService eligibility;
    private final SourceDedupDependencyService dependencies;
    private final GenerationFlowService.SettingsSnapshotSource settings;
    private final Clock clock;
    private final BooleanSupplier preparationAllowed;
    private final String valueResultVersion;
    private final SourceDayNewsValuePermitService dayPermits;

    public NewsValueSourcePlanPreparationService(JdbcFrozenSourcePlans storage,JdbcFrozenSourceUniverses universeStorage,
            FrozenSourceUniverseService originals,FrozenSourceStagePlanService plans,CollectionHandoffProofService collections,
            NewsValueEligibilityService eligibility,SourceDedupDependencyService dependencies,
            GenerationFlowService.SettingsSnapshotSource settings,Clock clock,BooleanSupplier preparationAllowed) {
        this(storage,universeStorage,originals,plans,collections,eligibility,dependencies,settings,clock,preparationAllowed,"1");
    }
    public NewsValueSourcePlanPreparationService(JdbcFrozenSourcePlans storage,JdbcFrozenSourceUniverses universeStorage,
            FrozenSourceUniverseService originals,FrozenSourceStagePlanService plans,CollectionHandoffProofService collections,
            NewsValueEligibilityService eligibility,SourceDedupDependencyService dependencies,
            GenerationFlowService.SettingsSnapshotSource settings,Clock clock,BooleanSupplier preparationAllowed,String valueResultVersion) {
        this(storage,universeStorage,originals,plans,collections,eligibility,dependencies,settings,clock,preparationAllowed,valueResultVersion,null);
    }
    public NewsValueSourcePlanPreparationService(JdbcFrozenSourcePlans storage,JdbcFrozenSourceUniverses universeStorage,
            FrozenSourceUniverseService originals,FrozenSourceStagePlanService plans,CollectionHandoffProofService collections,
            NewsValueEligibilityService eligibility,SourceDedupDependencyService dependencies,
            GenerationFlowService.SettingsSnapshotSource settings,Clock clock,BooleanSupplier preparationAllowed,String valueResultVersion,SourceDayNewsValuePermitService dayPermits){
        this.dayPermits=dayPermits;
        if(valueResultVersion==null||!java.util.Set.of("1","2").contains(valueResultVersion))throw unavailable();this.valueResultVersion=valueResultVersion;
        this.storage=Objects.requireNonNull(storage);this.universeStorage=Objects.requireNonNull(universeStorage);
        this.originals=Objects.requireNonNull(originals);this.plans=Objects.requireNonNull(plans);this.collections=Objects.requireNonNull(collections);
        this.eligibility=Objects.requireNonNull(eligibility);this.dependencies=Objects.requireNonNull(dependencies);
        this.settings=Objects.requireNonNull(settings);this.clock=Objects.requireNonNull(clock);this.preparationAllowed=Objects.requireNonNull(preparationAllowed);
    }

    private record CaptureData(MemberUniverse.Universe universe,FrozenSourceUniverse.Reference reference,FrozenSourceUniverse.Reference p1,
            CollectionHandoffProofService.Proof collection,NewsValueEligibilityService.Selection selection,
            FrozenSourceDedupDependency dependency,FrozenGenerationSettings settings,long generation,FrozenSourceDayDedupDependency day){ }
    public static final class Captured {
        private final CaptureData data;private final SourcePartitionPlanner.Plan original;private final SourceDedupPartialDependencyPermitService.Captured permit;private final SourceDayNewsValuePermitService.Captured day;
        private Captured(CaptureData data,SourcePartitionPlanner.Plan original){this(data,original,null);}
        private Captured(CaptureData data,SourcePartitionPlanner.Plan original,SourceDedupPartialDependencyPermitService.Captured permit){this(data,original,permit,null);}
        private Captured(CaptureData data,SourcePartitionPlanner.Plan original,SourceDedupPartialDependencyPermitService.Captured permit,SourceDayNewsValuePermitService.Captured day){this.data=data;this.original=original;this.permit=permit;this.day=day;}
        @Override public String toString(){return "CapturedNewsValuePlan[redacted]";}
    }
    public static final class PreparedPlan {
        private final Captured captured;private final FrozenSourceStagePlanService.ValidatedPlan validated;
        private PreparedPlan(Captured captured,FrozenSourceStagePlanService.ValidatedPlan validated){this.captured=captured;this.validated=validated;}
        public SourcePartitionPlanner.Plan plan(){return captured.original!=null?captured.original:validated.plan();}
        @Override public String toString(){return "PreparedNewsValuePlan[redacted]";}
    }
    /** Captures real P1 dependency and current qualification without constructing any provider request. */
    public Captured capture(String intentId,String runId,JsonNode originalDependency) {return captureInternal(intentId,runId,originalDependency,null);}
    /** Only an actual immutable capture can authorize partial qualification in this writer. */
    public Captured capturePartial(String intentId,String runId,SourceDedupPartialDependencyPermitService.Captured permit) {
        if(permit==null)throw unavailable();return captureInternal(intentId,runId,permit.dependency().toJson(),permit);
    }
    private Captured captureInternal(String intentId,String runId,JsonNode originalDependency,SourceDedupPartialDependencyPermitService.Captured permit) {
        return captureInternal(intentId,runId,originalDependency,permit,null);
    }
    public Captured captureDay(String intentId,String runId,JsonNode originalDependency,SourceDayNewsValuePermitService.Captured day){if(dayPermits==null||day==null)throw unavailable();return captureInternal(intentId,runId,originalDependency,null,day);}
    public Captured captureDayPartial(String intentId,String runId,SourceDedupPartialDependencyPermitService.Captured permit,SourceDayNewsValuePermitService.Captured day){if(dayPermits==null||day==null||permit==null)throw unavailable();return captureInternal(intentId,runId,permit.dependency().toJson(),permit,day);}
    private Captured captureInternal(String intentId,String runId,JsonNode originalDependency,SourceDedupPartialDependencyPermitService.Captured permit,SourceDayNewsValuePermitService.Captured day) {
        try {
            JdbcFrozenInputs.requireTx();if(dayPermits!=null&&day==null)throw unavailable();WireValues.id(intentId,false);WireValues.id(runId,false);var owner=storage.lockOwner(intentId);validate(owner);
            if(storage.receipt(intentId)!=null)return new Captured(null,replay(plans.read(intentId),runId,permit,day),permit,day);
            if(!"waiting_input".equals(owner.state())||storage.hasRoots(intentId)||!storage.parts(intentId).isEmpty())throw unavailable();
            if(!preparationAllowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();if(originalDependency==null)throw unavailable();
            var p1=FrozenSourceUniverse.Reference.fromJson(JdbcFrozenInputs.canonical(originalDependency.get("stage_result").get("universe_ref")));
            var universe=originals.readImmutable(p1);if(!runId.equals(universe.runId()))throw unavailable();
            var collection=collections.read(runId);var facts=collection.facts();
            if(!runId.equals(facts.runId())||!universe.sourceId().equals(facts.sourceId())||!owner.businessDate().equals(facts.businessDate())
                    ||!"news_tech".equals(facts.category())||!"production".equals(facts.activationStage())||!universe.manifestHash().equals(facts.manifestHash())
                    ||universe.members().size()!=facts.expected()||universe.accepted()!=facts.accepted()||universe.excluded()!=facts.excluded())throw unavailable();
            var selection=eligibility.capture(universe,owner.businessDate());var proven=prove(originalDependency,selection.cutoff(),permit);var dependency=proven.dependency();var full=dependency.toJson().get("stage_result");
            if(!runId.equals(proven.runId())||!universe.sourceId().equals(proven.sourceId())||!owner.businessDate().equals(proven.businessDate())
                    ||facts.generation()!=proven.generation()||!collection.intentId().equals(p1.ownerId())
                    ||!collection.intentId().equals(WireValues.id(originalDependency.get("stage_key").get("owner_job_id"),false))
                    ||!CollectionHandoffProofService.logicalKey(facts).equals(WireValues.text(full.get("logical_key")))
                    ||!p1.equals(FrozenSourceUniverse.Reference.fromJson(JdbcFrozenInputs.canonical(full.get("universe_ref"))))
                    ||!owner.key().equals(NewsValueSourcePlanBuilder.logicalKey(dependency)))throw unavailable();
            var originalDay=checkDay(dependency,selection.cutoff(),day,null);
            var copy=new FrozenSourceUniverseService(universeStorage,exactRun->{if(!runId.equals(exactRun))throw unavailable();return originals.readImmutable(p1);},clock);
            var reference=copy.freeze(intentId,runId);var frozenSettings=Objects.requireNonNull(settings.freeze());
            return new Captured(new CaptureData(universe,reference,p1,collection,selection,dependency,frozenSettings,owner.generation(),originalDay),null,permit,day);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsValueEligibilityService.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }
    /** Full pure P2 planning must finish outside all database transactions. */
    public PreparedPlan build(Captured captured) {
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();Objects.requireNonNull(captured);
            if(captured.original!=null)return new PreparedPlan(captured,null);var c=captured.data;
            boolean partial=Set.of("partial","no_usable_input").contains(c.collection().facts().deliveryState());
            var builder=new NewsValueSourcePlanBuilder(valueResultVersion);var plan=c.day()==null?builder.build(c.universe(),c.reference(),c.selection(),c.settings(),c.dependency(),c.generation(),partial):builder.buildDay(c.universe(),c.reference(),c.selection(),c.settings(),c.dependency(),c.day(),c.generation(),partial);
            return new PreparedPlan(captured,FrozenSourceStagePlanService.prevalidate(plan,c.universe()));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    /** Rechecks actual qualification, original observation and both archives before exact persistence. */
    public SourcePartitionPlanner.Plan commit(PreparedPlan prepared) {
        try {
            JdbcFrozenInputs.requireTx();Objects.requireNonNull(prepared);var plan=prepared.plan();String intent=plan.reference().ownerId(),run=plan.reference().runId();var owner=storage.lockOwner(intent);validate(owner);
            if(storage.receipt(intent)!=null){
                if(prepared.validated!=null){replay(plan,run,prepared.captured.permit,prepared.captured.day);plans.freezeValidated(prepared.validated);return plan;}
                var original=replay(plans.read(intent),run,prepared.captured.permit,prepared.captured.day);if(!plan.equals(original))throw unavailable();return original;
            }
            if(prepared.validated==null||!"waiting_input".equals(owner.state())||storage.hasRoots(intent)||!storage.parts(intent).isEmpty())throw unavailable();
            if(!preparationAllowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();var c=prepared.captured.data;
            checkDay(c.dependency(),c.selection().cutoff(),prepared.captured.day,c.day()==null?null:c.day().json());
            if(!c.collection().equals(collections.read(run))||!c.universe().equals(originals.readImmutable(c.p1()))
                    ||!eligibility.matchesCurrent(c.universe(),c.selection())||!c.settings().equals(settings.freeze())
                    ||!c.dependency().json().equals(prove(c.dependency().toJson(),c.selection().cutoff(),prepared.captured.permit).dependency().json()))throw unavailable();
            plans.freezeValidated(prepared.validated);return plan;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsValueEligibilityService.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }
    /** An original Universe prefix alone never establishes committed qualification or a plan. */
    public Captured captureCommitted(String intentId,String runId){var original=committed(intentId,runId);return original==null?null:new Captured(null,original);}
    public Captured captureCommittedPartial(String intentId,String runId,SourceDedupPartialDependencyPermitService.Captured permit){if(permit==null)throw unavailable();var original=committedPartial(intentId,runId,permit);return original==null?null:new Captured(null,original,permit);}
    public Captured captureCommittedDay(String intentId,String runId,SourceDayNewsValuePermitService.Captured day,SourceDedupPartialDependencyPermitService.Captured permit){requireDayLookup(day);var original=committedInternal(intentId,runId,permit,day);return original==null?null:new Captured(null,original,permit,day);}

    /** Compatibility entry retaining the original single-transaction contract. */
    public SourcePartitionPlanner.Plan prepare(String intentId,String runId,JsonNode originalDependency) {
        try {
            if(dayPermits!=null)throw unavailable();
            JdbcFrozenInputs.requireTx();WireValues.id(intentId,false);WireValues.id(runId,false);
            var owner=storage.lockOwner(intentId);validate(owner);
            if(storage.receipt(intentId)!=null)return replay(plans.read(intentId),runId);
            if(!"waiting_input".equals(owner.state())||storage.hasRoots(intentId)||!storage.parts(intentId).isEmpty())throw unavailable();
            if(!preparationAllowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();
            if(originalDependency==null)throw unavailable();
            var originalReference=FrozenSourceUniverse.Reference.fromJson(JdbcFrozenInputs.canonical(originalDependency.get("stage_result").get("universe_ref")));
            var universe=originals.readImmutable(originalReference);
            if(!runId.equals(universe.runId()))throw unavailable();
            var collection=collections.read(runId);var facts=collection.facts();
            if(!runId.equals(facts.runId())||!universe.sourceId().equals(facts.sourceId())||!owner.businessDate().equals(facts.businessDate())
                    ||!"news_tech".equals(facts.category())||!"production".equals(facts.activationStage())
                    ||!universe.manifestHash().equals(facts.manifestHash())||universe.members().size()!=facts.expected()
                    ||universe.accepted()!=facts.accepted()||universe.excluded()!=facts.excluded())throw unavailable();
            var selection=eligibility.capture(universe,owner.businessDate());var proven=dependencies.read(originalDependency,selection.cutoff());
            var dependency=proven.dependency();var full=dependency.toJson().get("stage_result");
            if(!runId.equals(proven.runId())||!universe.sourceId().equals(proven.sourceId())||!owner.businessDate().equals(proven.businessDate())
                    ||facts.generation()!=proven.generation()||!collection.intentId().equals(originalReference.ownerId())
                    ||!collection.intentId().equals(WireValues.id(originalDependency.get("stage_key").get("owner_job_id"),false))
                    ||!CollectionHandoffProofService.logicalKey(facts).equals(WireValues.text(full.get("logical_key")))
                    ||!originalReference.equals(FrozenSourceUniverse.Reference.fromJson(JdbcFrozenInputs.canonical(full.get("universe_ref"))))
                    ||!owner.key().equals(NewsValueSourcePlanBuilder.logicalKey(dependency)))throw unavailable();
            var copy=new FrozenSourceUniverseService(universeStorage,exactRun->{
                if(!runId.equals(exactRun))throw unavailable();return originals.readImmutable(originalReference);
            },clock);
            var reference=copy.freeze(intentId,runId);
            var frozenSettings=Objects.requireNonNull(settings.freeze());
            boolean partial=Set.of("partial","no_usable_input").contains(facts.deliveryState());
            var plan=new NewsValueSourcePlanBuilder(valueResultVersion).build(universe,reference,selection,frozenSettings,dependency,owner.generation(),partial);
            return replay(plans.read(plans.freeze(plan)),runId);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsValueEligibilityService.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }

    /** Original archive only, including lookup after a successfully committed but lost ACK. */
    public SourcePartitionPlanner.Plan committed(String intentId,String runId) {return committedInternal(intentId,runId,null);}
    public SourcePartitionPlanner.Plan committedPartial(String intentId,String runId,SourceDedupPartialDependencyPermitService.Captured permit){if(permit==null)throw unavailable();return committedInternal(intentId,runId,permit);}
    private SourcePartitionPlanner.Plan committedInternal(String intentId,String runId,SourceDedupPartialDependencyPermitService.Captured permit) {
        return committedInternal(intentId,runId,permit,null);
    }
    public SourcePartitionPlanner.Plan committedDay(String intentId,String runId,SourceDayNewsValuePermitService.Captured day,SourceDedupPartialDependencyPermitService.Captured permit){requireDayLookup(day);return committedInternal(intentId,runId,permit,day);}
    private SourcePartitionPlanner.Plan committedInternal(String intentId,String runId,SourceDedupPartialDependencyPermitService.Captured permit,SourceDayNewsValuePermitService.Captured day) {
        try {
            WireValues.id(intentId,false);WireValues.id(runId,false);validate(storage.immutableOwner(intentId));
            if(storage.immutableReceipt(intentId)==null){if(!storage.immutableParts(intentId).isEmpty())throw unavailable();return null;}
            return replay(plans.readImmutable(intentId),runId,permit,day);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    private SourcePartitionPlanner.Plan replay(SourcePartitionPlanner.Plan plan,String runId){return replay(plan,runId,null);}
    private SourcePartitionPlanner.Plan replay(SourcePartitionPlanner.Plan plan,String runId,SourceDedupPartialDependencyPermitService.Captured permit) {
        return replay(plan,runId,permit,null);
    }
    private SourcePartitionPlanner.Plan replay(SourcePartitionPlanner.Plan plan,String runId,SourceDedupPartialDependencyPermitService.Captured permit,SourceDayNewsValuePermitService.Captured day) {
        if(plan.stage()!=GenerationFlowService.Stage.NEWS_VALUE||plan.scope()==null||plan.scope().stageContextJson()==null||!runId.equals(plan.reference().runId()))throw unavailable();
        String raw=plan.scope().stageContextJson();var context=CanonicalJson.parse(SourceValueStageContext.readAny(raw,plan.scope().businessDate(),plan.scope().cutoff()));
        if(permit!=null&&!permit.dependency().json().equals(JdbcFrozenInputs.canonical(context.get(FrozenSourceDedupDependency.FIELD))))throw unavailable();var proven=prove(context.get(FrozenSourceDedupDependency.FIELD),plan.scope().cutoff(),permit);
        if(!runId.equals(proven.runId())||!plan.scope().businessDate().equals(proven.businessDate())||!plan.logicalKey().equals(NewsValueSourcePlanBuilder.logicalKey(proven.dependency())))throw unavailable();
        if(dayPermits!=null&&!context.has(FrozenSourceDayDedupDependency.FIELD))throw unavailable();
        if(context.has(FrozenSourceDayDedupDependency.FIELD)){String saved=JdbcFrozenInputs.canonical(context.get(FrozenSourceDayDedupDependency.FIELD));FrozenSourceDayDedupDependency.read(saved,proven.dependency(),plan.scope().cutoff());if(day!=null||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){if(dayPermits==null)throw unavailable();checkDay(proven.dependency(),plan.scope().cutoff(),day,saved);}}
        return plan;
    }
    private FrozenSourceDayDedupDependency checkDay(FrozenSourceDedupDependency own,java.time.Instant cutoff,SourceDayNewsValuePermitService.Captured day,String expected){
        if(dayPermits==null){if(day!=null||expected!=null)throw unavailable();return null;}if(day==null)throw unavailable();var value=TransactionSynchronizationManager.isCurrentTransactionReadOnly()?dayPermits.verifyObserved(day,cutoff):dayPermits.verify(day,cutoff);if(value==null||!value.json().equals(day.dependency().json())||expected!=null&&!expected.equals(value.json()))throw unavailable();return FrozenSourceDayDedupDependency.read(value.json(),own,cutoff);
    }
    private void requireDayLookup(SourceDayNewsValuePermitService.Captured day){if(!TransactionSynchronizationManager.isActualTransactionActive()||dayPermits==null||day==null)throw unavailable();}
    private SourceDedupDependencyService.Proven prove(JsonNode profile,java.time.Instant cutoff,SourceDedupPartialDependencyPermitService.Captured permit){
        if(!"source-dedup-dependency-v2".equals(WireValues.text(profile.get("schema_version"))))return dependencies.read(profile,cutoff);
        var proven=TransactionSynchronizationManager.isCurrentTransactionReadOnly()?dependencies.readPartial(profile,cutoff):dependencies.readPartial(Objects.requireNonNull(permit),cutoff);
        if(!proven.dependency().json().equals(JdbcFrozenInputs.canonical(profile)))throw unavailable();return proven;
    }
    private static void validate(JdbcFrozenSourcePlans.Owner owner) {
        if(owner==null||!"generation_intent".equals(owner.kind())||!"news_value".equals(owner.type())||owner.key()==null||owner.key().isBlank()
                ||!Arrays.equals(owner.keyHash(),JdbcGenerationLineage.hash(owner.key()))||owner.generation()<1||owner.generation()>CanonicalJson.MAX_SAFE_INTEGER
                ||owner.businessDate()==null||!owner.id().equals(JdbcFrozenInputs.stableId("intent",owner.key()+":"+owner.generation())))throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("actual news value source plan preparation unavailable");}
}
