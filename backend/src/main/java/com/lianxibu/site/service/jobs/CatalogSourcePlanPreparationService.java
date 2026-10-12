package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcFrozenSourcePlans;
import com.lianxibu.site.repository.jobs.JdbcGenerationLineage;
import com.lianxibu.site.service.articles.NewsCandidateRecall;
import com.lianxibu.site.service.articles.CatalogDedupEligibilityService;
import java.util.Arrays;
import java.util.Set;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins the shared writer to archive one complete P3-P6 plan from trusted original providers. */
public final class CatalogSourcePlanPreparationService {
    private final JdbcFrozenSourcePlans storage;
    private final FrozenSourceUniverseService universes;
    private final FrozenSourceStagePlanService plans;
    private final CollectionHandoffProofService collections;
    private final CatalogDedupEligibilityService eligibility;
    private final java.util.function.Supplier<NewsCandidateRecall.CategoryHistory> history;
    private final String category;
    private final GenerationFlowService.SettingsSnapshotSource settings;
    private final BooleanSupplier preparationAllowed;
    private final CatalogSourceVersionPlanPermitService versions;

    public CatalogSourcePlanPreparationService(JdbcFrozenSourcePlans storage,FrozenSourceUniverseService universes,
            FrozenSourceStagePlanService plans,CollectionHandoffProofService collections,
            CatalogDedupEligibilityService eligibility,java.util.function.Supplier<NewsCandidateRecall.CategoryHistory> history,String category,
            GenerationFlowService.SettingsSnapshotSource settings,BooleanSupplier preparationAllowed) {
        this(storage,universes,plans,collections,eligibility,history,category,settings,preparationAllowed,null);
    }
    public CatalogSourcePlanPreparationService(JdbcFrozenSourcePlans storage,FrozenSourceUniverseService universes,
            FrozenSourceStagePlanService plans,CollectionHandoffProofService collections,
            CatalogDedupEligibilityService eligibility,java.util.function.Supplier<NewsCandidateRecall.CategoryHistory> history,String category,
            GenerationFlowService.SettingsSnapshotSource settings,BooleanSupplier preparationAllowed,CatalogSourceVersionPlanPermitService versions) {
        this.versions=versions;
        this.storage=Objects.requireNonNull(storage);this.universes=Objects.requireNonNull(universes);
        this.plans=Objects.requireNonNull(plans);this.collections=Objects.requireNonNull(collections);
        this.eligibility=Objects.requireNonNull(eligibility);if(category==null||!Set.of("agent","skill","workflow","model").contains(category))throw unavailable();this.category=category;
        this.history=Objects.requireNonNull(history);
        this.settings=Objects.requireNonNull(settings);this.preparationAllowed=Objects.requireNonNull(preparationAllowed);
    }

    private record CaptureData(MemberUniverse.Universe universe,FrozenSourceUniverse.Reference reference,
            CollectionHandoffProofService.Proof collection,CatalogDedupEligibilityService.Selection selection,
            NewsCandidateRecall.CategoryHistory history,FrozenGenerationSettings settings){ }
    public static final class Captured {
        private final CatalogSourcePlanPreparationService issuer;private final CaptureData data;private final SourcePartitionPlanner.Plan original;
        private Captured(CatalogSourcePlanPreparationService issuer,CaptureData data,SourcePartitionPlanner.Plan original){this.issuer=issuer;this.data=data;this.original=original;}
        @Override public String toString(){return "CapturedCatalogSourcePlan[redacted]";}
    }
    public static final class PreparedPlan {
        private final Captured captured;private final FrozenSourceStagePlanService.ValidatedPlan validated;private final CatalogSourceVersionPlanPermitService.Captured versions;
        private PreparedPlan(Captured captured,FrozenSourceStagePlanService.ValidatedPlan validated,CatalogSourceVersionPlanPermitService.Captured versions){this.captured=captured;this.validated=validated;this.versions=versions;}
        public SourcePartitionPlanner.Plan plan(){return captured.original!=null?captured.original:validated.plan();}
        @Override public String toString(){return "PreparedCatalogSourcePlan[redacted]";}
    }

    /** Captures actual current facts under the original short shared writer, without request construction. */
    public Captured capture(String intentId,String runId) {
        try {
            JdbcFrozenInputs.requireTx();WireValues.id(intentId,false);WireValues.id(runId,false);
            var owner=storage.lockOwner(intentId);validate(owner);
            if(storage.receipt(intentId)!=null){var original=plans.read(intentId);if(!runId.equals(original.reference().runId()))throw unavailable();return new Captured(this,null,original);}
            if(!preparationAllowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();
            if(!"waiting_input".equals(owner.state())||storage.hasRoots(intentId)||!storage.parts(intentId).isEmpty())throw unavailable();
            var collection=collections.read(runId);var facts=collection.facts();
            if(!intentId.equals(collection.intentId())||!owner.key().equals(CollectionHandoffProofService.logicalKey(facts))
                    ||owner.generation()!=facts.generation()||!owner.businessDate().equals(facts.businessDate())
                    ||!category.equals(facts.category())||!"production".equals(facts.activationStage()))throw unavailable();
            var reference=universes.freeze(intentId,runId);var original=universes.read(reference);var captured=Objects.requireNonNull(history.get());
            if(!category.equals(captured.category()))throw unavailable();var selection=eligibility.read(original,category,owner.businessDate(),captured.facts().cutoff());var frozenSettings=Objects.requireNonNull(settings.freeze());
            return new Captured(this,new CaptureData(original,reference,collection,selection,captured,frozenSettings),null);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(CatalogDedupEligibilityService.CapacityGuarded|NewsCandidateRecall.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }

    /** Capture native version proofs outside the writer, then construct the complete original request purely. */
    public PreparedPlan build(Captured captured) {
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();Objects.requireNonNull(captured);if(captured.issuer!=this)throw unavailable();
            if(captured.original!=null)return new PreparedPlan(captured,null,null);
            var c=captured.data;var qualified=versions==null?null:versions.capture(c.selection(),c.history());var builder=new CatalogSourcePlanBuilder();
            var plan=qualified==null?builder.build(c.universe(),c.reference(),c.selection(),c.collection(),c.settings(),c.history())
                :builder.buildWithVersions(c.universe(),c.reference(),c.selection(),c.collection(),c.settings(),c.history(),qualified.projections());
            return new PreparedPlan(captured,FrozenSourceStagePlanService.prevalidate(plan,c.universe()),qualified);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(NewsCandidateRecall.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }

    /** Rechecks current facts after pure computation and persists the exact validated original. */
    public SourcePartitionPlanner.Plan commit(PreparedPlan prepared) {
        try {
            JdbcFrozenInputs.requireTx();Objects.requireNonNull(prepared);if(prepared.captured.issuer!=this)throw unavailable();var plan=prepared.plan();String intent=plan.reference().ownerId();var owner=storage.lockOwner(intent);validate(owner);
            if(storage.receipt(intent)!=null){
                if(prepared.validated!=null){plans.freezeValidated(prepared.validated);return plan;}
                if(!plan.equals(plans.read(intent)))throw unavailable();return plan;
            }
            if(prepared.validated==null||!"waiting_input".equals(owner.state())||storage.hasRoots(intent)||!storage.parts(intent).isEmpty())throw unavailable();
            if(!preparationAllowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();
            var c=prepared.captured.data;
            if(!c.collection().equals(collections.read(c.universe().runId()))
                    ||!c.selection().equals(eligibility.read(c.universe(),category,owner.businessDate(),c.history().facts().cutoff()))
                    ||!sameHistory(c.history(),history.get())||!c.settings().equals(settings.freeze()))throw unavailable();
            if(versions!=null){if(prepared.versions==null)throw unavailable();versions.verify(prepared.versions);}
            else if(prepared.versions!=null)throw unavailable();
            plans.freezeValidated(prepared.validated);return plan;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(CatalogDedupEligibilityService.CapacityGuarded|NewsCandidateRecall.CapacityGuarded guarded){throw new GenerationFlowService.CapacityGuarded();}
        catch(RuntimeException rejected){throw unavailable();}
    }

    /** A Universe prefix is not a committed plan or a durable qualification capture. */
    public Captured captureCommitted(String intentId,String runId){var original=committed(intentId,runId);return original==null?null:new Captured(this,null,original);}
    private static boolean sameHistory(NewsCandidateRecall.CategoryHistory original,NewsCandidateRecall.CategoryHistory current){
        if(current==null||!original.category().equals(current.category()))return false;return sameFacts(original.facts(),current.facts());
    }
    private static boolean sameFacts(NewsCandidateRecall.History original,NewsCandidateRecall.History current){
        if(current==null||current.cutoff().isBefore(original.cutoff())||current.entities().size()!=original.entities().size())return false;
        for(int n=0;n<original.entities().size();n++){
            var a=original.entities().get(n);var b=current.entities().get(n);
            if(!a.id().equals(b.id())||!a.identityKey().equals(b.identityKey())||!a.title().equals(b.title())||!Objects.equals(a.summary(),b.summary())
                    ||a.revision()!=b.revision()||!a.firstRecordedAt().equals(b.firstRecordedAt())||!a.analysisState().equals(b.analysisState())||a.legacy()!=b.legacy())return false;
        }
        return true;
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

    private void validate(JdbcFrozenSourcePlans.Owner owner) {
        if(owner==null||!"generation_intent".equals(owner.kind())||!(category+"_dedup").equals(owner.type())
                ||owner.key()==null||owner.key().isBlank()||!Arrays.equals(owner.keyHash(),JdbcGenerationLineage.hash(owner.key()))
                ||owner.generation()<1||owner.generation()>CanonicalJson.MAX_SAFE_INTEGER||owner.businessDate()==null)throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("actual catalog source plan preparation unavailable");}
}
