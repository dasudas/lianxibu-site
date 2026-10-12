package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcFrozenSourcePlans;
import com.lianxibu.site.repository.jobs.JdbcGenerationLineage;
import com.lianxibu.site.service.articles.NewsCandidateRecall;
import com.lianxibu.site.service.articles.NewsDedupEligibilityService;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins the shared writer to archive one complete P1 plan from trusted original providers. */
public final class NewsSourcePlanPreparationService {
    private final JdbcFrozenSourcePlans storage;
    private final FrozenSourceUniverseService universes;
    private final FrozenSourceStagePlanService plans;
    private final CollectionHandoffProofService collections;
    private final NewsDedupEligibilityService eligibility;
    private final NewsCandidateRecall.HistorySource history;
    private final GenerationFlowService.SettingsSnapshotSource settings;
    private final BooleanSupplier preparationAllowed;

    public NewsSourcePlanPreparationService(JdbcFrozenSourcePlans storage,FrozenSourceUniverseService universes,
            FrozenSourceStagePlanService plans,CollectionHandoffProofService collections,
            NewsDedupEligibilityService eligibility,NewsCandidateRecall.HistorySource history,
            GenerationFlowService.SettingsSnapshotSource settings,BooleanSupplier preparationAllowed) {
        this.storage=Objects.requireNonNull(storage);this.universes=Objects.requireNonNull(universes);
        this.plans=Objects.requireNonNull(plans);this.collections=Objects.requireNonNull(collections);
        this.eligibility=Objects.requireNonNull(eligibility);this.history=Objects.requireNonNull(history);
        this.settings=Objects.requireNonNull(settings);this.preparationAllowed=Objects.requireNonNull(preparationAllowed);
    }

    private record CaptureData(MemberUniverse.Universe universe,FrozenSourceUniverse.Reference reference,
            CollectionHandoffProofService.Proof collection,NewsDedupEligibilityService.Selection selection,
            NewsCandidateRecall.History history,FrozenGenerationSettings settings){ }
    public static final class Captured {
        private final CaptureData data;private final SourcePartitionPlanner.Plan original;
        private Captured(CaptureData data,SourcePartitionPlanner.Plan original){this.data=data;this.original=original;}
        @Override public String toString(){return "CapturedNewsSourcePlan[redacted]";}
    }
    public static final class PreparedPlan {
        private final Captured captured;private final FrozenSourceStagePlanService.ValidatedPlan validated;
        private PreparedPlan(Captured captured,FrozenSourceStagePlanService.ValidatedPlan validated){this.captured=captured;this.validated=validated;}
        public SourcePartitionPlanner.Plan plan(){return captured.original!=null?captured.original:validated.plan();}
        @Override public String toString(){return "PreparedNewsSourcePlan[redacted]";}
    }

    /** Captures actual current facts under the original short shared writer, without request construction. */
    public Captured capture(String intentId,String runId) {
        try {
            JdbcFrozenInputs.requireTx();WireValues.id(intentId,false);WireValues.id(runId,false);
            var owner=storage.lockOwner(intentId);validate(owner);
            if(storage.receipt(intentId)!=null){var original=plans.read(intentId);if(!runId.equals(original.reference().runId()))throw unavailable();return new Captured(null,original);}
            if(!preparationAllowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();
            if(!"waiting_input".equals(owner.state())||storage.hasRoots(intentId)||!storage.parts(intentId).isEmpty())throw unavailable();
            var collection=collections.read(runId);var facts=collection.facts();
            if(!intentId.equals(collection.intentId())||!owner.key().equals(CollectionHandoffProofService.logicalKey(facts))
                    ||owner.generation()!=facts.generation()||!owner.businessDate().equals(facts.businessDate())
                    ||!"news_tech".equals(facts.category())||!"production".equals(facts.activationStage()))throw unavailable();
            var reference=universes.freeze(intentId,runId);var original=universes.read(reference);var captured=Objects.requireNonNull(history.capture());
            var selection=eligibility.read(original,owner.businessDate(),captured.cutoff());var frozenSettings=Objects.requireNonNull(settings.freeze());
            return new Captured(new CaptureData(original,reference,collection,selection,captured,frozenSettings),null);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsDedupEligibilityService.CapacityGuarded|NewsCandidateRecall.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }

    /** Complete original request generation is pure and must run outside any database transaction. */
    public PreparedPlan build(Captured captured) {
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();Objects.requireNonNull(captured);
            if(captured.original!=null)return new PreparedPlan(captured,null);
            var c=captured.data;var plan=new NewsSourcePlanBuilder().build(c.universe(),c.reference(),c.selection(),c.collection(),c.settings(),c.history());
            return new PreparedPlan(captured,FrozenSourceStagePlanService.prevalidate(plan,c.universe()));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsCandidateRecall.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }

    /** Rechecks current facts after pure computation and persists the exact validated original. */
    public SourcePartitionPlanner.Plan commit(PreparedPlan prepared) {
        try {
            JdbcFrozenInputs.requireTx();Objects.requireNonNull(prepared);var plan=prepared.plan();String intent=plan.reference().ownerId();var owner=storage.lockOwner(intent);validate(owner);
            if(storage.receipt(intent)!=null){
                if(prepared.validated!=null){plans.freezeValidated(prepared.validated);return plan;}
                if(!plan.equals(plans.read(intent)))throw unavailable();return plan;
            }
            if(prepared.validated==null||!"waiting_input".equals(owner.state())||storage.hasRoots(intent)||!storage.parts(intent).isEmpty())throw unavailable();
            if(!preparationAllowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();
            var c=prepared.captured.data;
            if(!c.collection().equals(collections.read(c.universe().runId()))
                    ||!c.selection().equals(eligibility.read(c.universe(),owner.businessDate(),c.history().cutoff()))
                    ||!sameHistory(c.history(),history.capture())||!c.settings().equals(settings.freeze()))throw unavailable();
            plans.freezeValidated(prepared.validated);return plan;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsDedupEligibilityService.CapacityGuarded|NewsCandidateRecall.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }

    /** A Universe prefix is not a committed plan or a durable qualification capture. */
    public Captured captureCommitted(String intentId,String runId){var original=committed(intentId,runId);return original==null?null:new Captured(null,original);}
    private static boolean sameHistory(NewsCandidateRecall.History original,NewsCandidateRecall.History current){
        if(current==null||current.cutoff().isBefore(original.cutoff())||current.entities().size()!=original.entities().size())return false;
        for(int n=0;n<original.entities().size();n++){
            var a=original.entities().get(n);var b=current.entities().get(n);
            if(!a.id().equals(b.id())||!a.identityKey().equals(b.identityKey())||!a.title().equals(b.title())||!Objects.equals(a.summary(),b.summary())
                    ||a.revision()!=b.revision()||!a.firstRecordedAt().equals(b.firstRecordedAt())||!a.analysisState().equals(b.analysisState())||a.legacy()!=b.legacy())return false;
        }
        return true;
    }

    /** Compatibility entry retaining the original single-transaction contract. */
    public SourcePartitionPlanner.Plan prepare(String intentId,String runId) {
        try {
            JdbcFrozenInputs.requireTx();WireValues.id(intentId,false);WireValues.id(runId,false);
            if(!preparationAllowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();
            var owner=storage.lockOwner(intentId);validate(owner);
            if(storage.receipt(intentId)!=null) {
                var original=plans.read(intentId);
                if(!runId.equals(original.reference().runId()))throw unavailable();
                return original;
            }
            if(!"waiting_input".equals(owner.state())||storage.hasRoots(intentId)||!storage.parts(intentId).isEmpty())throw unavailable();
            var collection=collections.read(runId);var facts=collection.facts();
            if(!intentId.equals(collection.intentId())||!owner.key().equals(CollectionHandoffProofService.logicalKey(facts))
                    ||owner.generation()!=facts.generation()||!owner.businessDate().equals(facts.businessDate())
                    ||!"news_tech".equals(facts.category())||!"production".equals(facts.activationStage()))throw unavailable();
            var reference=universes.freeze(intentId,runId);var original=universes.read(reference);
            var captured=Objects.requireNonNull(history.capture());
            var selection=eligibility.read(original,owner.businessDate(),captured.cutoff());
            var frozenSettings=Objects.requireNonNull(settings.freeze());
            var plan=new NewsSourcePlanBuilder().build(original,reference,selection,collection,frozenSettings,captured);
            return plans.read(plans.freeze(plan));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsDedupEligibilityService.CapacityGuarded|NewsCandidateRecall.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }

    /** Original committed plan lookup for the caller's commit-unknown recovery transaction. */
    public SourcePartitionPlanner.Plan committed(String intentId,String runId) {
        try {
            WireValues.id(intentId,false);WireValues.id(runId,false);
            validate(storage.immutableOwner(intentId));
            if(storage.immutableReceipt(intentId)==null) {
                if(!storage.immutableParts(intentId).isEmpty())throw unavailable();return null;
            }
            var original=plans.readImmutable(intentId);
            if(!runId.equals(original.reference().runId()))throw unavailable();return original;
        }catch(RuntimeException rejected){throw unavailable();}
    }

    private static void validate(JdbcFrozenSourcePlans.Owner owner) {
        if(owner==null||!"generation_intent".equals(owner.kind())||!"news_dedup".equals(owner.type())
                ||owner.key()==null||owner.key().isBlank()||!Arrays.equals(owner.keyHash(),JdbcGenerationLineage.hash(owner.key()))
                ||owner.generation()<1||owner.generation()>CanonicalJson.MAX_SAFE_INTEGER||owner.businessDate()==null)throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("actual news source plan preparation unavailable");}
}
