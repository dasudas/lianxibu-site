package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcSourceDedupResults;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;

/** Joins the caller's shared writer to prepare one exact archived source chunk.
 * Qualification, global gates and the permanent scan cursor belong to the caller. */
public final class SourceStagePreparationService {
    public record Progress(SourcePartitionPlanner.Plan plan,long nextPart){
        public Progress {Objects.requireNonNull(plan);if(nextPart<0||nextPart>plan.chunks().size())throw unavailable();}
        public boolean complete(){return nextPart==plan.chunks().size();}
        @Override public String toString(){return "OriginalSourcePreparationProgress[redacted]";}
    }
    private final DataSource source;
    private final FrozenSourceStagePlanService plans;
    private final FrozenSourceUniverseService universes;
    private final CollectionProofSource collections;
    private final GenerationLineageService.BirthSource births;
    private final SourceDedupDependencyService sourceDedup;
    private final Clock clock;
    private final String actor;

    public SourceStagePreparationService(DataSource source,FrozenSourceStagePlanService plans,
            FrozenSourceUniverseService universes,CollectionProofSource collections,Clock clock,String actor) {
        this(source,plans,universes,collections,clock,actor,null);
    }
    public SourceStagePreparationService(DataSource source,FrozenSourceStagePlanService plans,
            FrozenSourceUniverseService universes,CollectionProofSource collections,Clock clock,String actor,
            GenerationLineageService.BirthSource births) {this(source,plans,universes,collections,clock,actor,births,null);}
    public SourceStagePreparationService(DataSource source,FrozenSourceStagePlanService plans,
            FrozenSourceUniverseService universes,CollectionProofSource collections,Clock clock,String actor,
            GenerationLineageService.BirthSource births,SourceDedupDependencyService sourceDedup) {
        this.source=Objects.requireNonNull(source);this.plans=Objects.requireNonNull(plans);
        this.universes=Objects.requireNonNull(universes);
        this.collections=Objects.requireNonNull(collections);this.clock=Objects.requireNonNull(clock);this.actor=WireValues.id(actor,false);this.births=births;this.sourceDedup=sourceDedup;
    }

    /** Opaque original archive plus pure work. It never confers current write permission. */
    public static final class CapturedPart {
        private final SourceStagePreparationService service;
        private final String intentId,actionKey;private final long stagePart;
        private final FrozenSourceStagePlanService.OriginalPlan original;
        private final Prepare command;private final GenerationJdbcStore store;
        private final SourceDedupPartialDependencyPermitService.Captured permit;
        private CapturedPart(SourceStagePreparationService service,String intentId,long stagePart,String actionKey,FrozenSourceStagePlanService.OriginalPlan original,Prepare command,GenerationJdbcStore store,SourceDedupPartialDependencyPermitService.Captured permit){this.service=service;this.intentId=intentId;this.stagePart=stagePart;this.actionKey=actionKey;this.original=original;this.command=command;this.store=store;this.permit=permit;}
        @Override public String toString(){return "OriginalSourcePartCapture[redacted]";}
    }
    public CapturedPart capturePart(String intentId,long stagePart,String actionKey){return capturePartInternal(intentId,stagePart,actionKey,null);}
    public CapturedPart capturePartPartial(String intentId,long stagePart,String actionKey,SourceDedupPartialDependencyPermitService.Captured permit){if(permit==null)throw unavailable();return capturePartInternal(intentId,stagePart,actionKey,permit);}
    private CapturedPart capturePartInternal(String intentId,long stagePart,String actionKey,SourceDedupPartialDependencyPermitService.Captured permit){
        try {
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();identity(intentId,stagePart,actionKey);
            var reads=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));reads.setReadOnly(true);reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var archived=Objects.requireNonNull(reads.execute(status->plans.readImmutableOriginal(intentId)));
            var plan=archived.plan();if(stagePart>=plan.chunks().size())throw unavailable();var chunk=plan.chunks().get((int)stagePart);
            requireOriginalChunk(plan,chunk);partialProfile(CanonicalJson.parse(chunk.snapshotJson()),permit);
            var command=command(intentId,stagePart,plan,chunk,archived.universe());var store=store(chunk,command,permit);
            store.primePureInput(frozen(plan,chunk,plan.scope().cutoff()));
            return new CapturedPart(this,intentId,stagePart,actionKey,archived,command,store,permit);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    /** Reuses only pure work; reconstructs complete original archives and receipts in each caller transaction. */
    public Frozen committedCaptured(CapturedPart captured){
        try {
            if(captured==null||captured.service!=this)throw unavailable();identity(captured.intentId,captured.stagePart,captured.actionKey);
            var actual=plans.verifyCapturedImmutableOriginal(captured.original);
            if(!actual.plan().equals(captured.original.plan())||!actual.universe().equals(captured.original.universe()))throw unavailable();
            var prior=captured.store.committed(captured.actionKey);
            if(prior!=null)matches(prior,actual.plan().chunks().get((int)captured.stagePart),actual.plan());return prior;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    public Frozen prepareCaptured(CapturedPart captured){
        try {
            JdbcFrozenInputs.requireTx();if(captured==null||captured.service!=this)throw unavailable();
            identity(captured.intentId,captured.stagePart,captured.actionKey);
            var actual=plans.verifyCapturedOriginal(captured.original);
            if(!actual.plan().equals(captured.original.plan())||!actual.universe().equals(captured.original.universe()))throw unavailable();
            var plan=actual.plan();var chunk=plan.chunks().get((int)captured.stagePart);
            return writeOriginal(captured.intentId,captured.actionKey,plan,chunk,captured.command,captured.store);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    public Frozen preparePart(String intentId,long stagePart,String actionKey){return prepareInternal(intentId,stagePart,actionKey,null);}
    public Frozen preparePartPartial(String intentId,long stagePart,String actionKey,SourceDedupPartialDependencyPermitService.Captured permit){if(permit==null)throw unavailable();return prepareInternal(intentId,stagePart,actionKey,permit);}
    private Frozen prepareInternal(String intentId,long stagePart,String actionKey,SourceDedupPartialDependencyPermitService.Captured permit) {
        try {
            JdbcFrozenInputs.requireTx();identity(intentId,stagePart,actionKey);
            var archived=plans.readOriginal(intentId);var plan=archived.plan();if(stagePart>=plan.chunks().size())throw unavailable();
            var chunk=plan.chunks().get((int)stagePart);var original=archived.universe();
            requireOriginalChunk(plan,chunk);
            var snapshot=CanonicalJson.parse(chunk.snapshotJson());partialProfile(snapshot,permit);
            var command=command(intentId,stagePart,plan,chunk,original);
            var store=store(chunk,command,permit);
            return writeOriginal(intentId,actionKey,plan,chunk,command,store);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw unavailable();}
    }

    private Frozen writeOriginal(String intentId,String actionKey,SourcePartitionPlanner.Plan plan,SourcePartitionPlanner.Chunk chunk,Prepare command,GenerationJdbcStore store){
        var prior=store.committed(actionKey);if(prior!=null){matches(prior,chunk,plan);return prior;}
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(plan.scope().cutoff().isAfter(now)||!GenerationFlowService.ready(command,plan.scope().cutoff()))throw unavailable();
        CollectionInputProofs.verify(command,collections,plan.stage().dedup());
        if(!plan.stage().dedup()&&CanonicalJson.parse(chunk.snapshotJson()).has(FrozenCollectionManifest.FIELD))throw unavailable();
        var value=frozen(plan,chunk,now);store.save(value,actionKey);var committed=store.committed(actionKey);
        if(!value.equals(committed))throw unavailable();return committed;
    }
    private static Frozen frozen(SourcePartitionPlanner.Plan plan,SourcePartitionPlanner.Chunk chunk,Instant now){
        var snapshot=CanonicalJson.parse(chunk.snapshotJson());var missing=strings(snapshot.get("missing"));String coverage=WireValues.text(snapshot.get("coverage_state"));
        if(!(missing.isEmpty()?"complete":"partial").equals(coverage))throw unavailable();
        return new Frozen(chunk.rootId(),plan.logicalKey(),plan.generation(),plan.stage(),plan.scope().cutoff(),coverage,missing,chunk.snapshotJson(),chunk.inputHash(),chunk.requestJson(),plan.stage().priority(),GenerationFlowService.nextEligible(now),"queued");
    }
    /** Recovers only the exact permanent receipt and original archives in the caller's transaction. */
    public Frozen committedPart(String intentId,long stagePart,String actionKey){return committedInternal(intentId,stagePart,actionKey,null);}
    public Frozen committedPartPartial(String intentId,long stagePart,String actionKey,SourceDedupPartialDependencyPermitService.Captured permit){if(permit==null)throw unavailable();return committedInternal(intentId,stagePart,actionKey,permit);}
    private Frozen committedInternal(String intentId,long stagePart,String actionKey,SourceDedupPartialDependencyPermitService.Captured permit) {
        try {
            identity(intentId,stagePart,actionKey);
            var archived=plans.readImmutableOriginal(intentId);var plan=archived.plan();if(stagePart>=plan.chunks().size())throw unavailable();
            var chunk=plan.chunks().get((int)stagePart);var original=archived.universe();
            partialProfile(CanonicalJson.parse(chunk.snapshotJson()),permit);var prior=store(chunk,command(intentId,stagePart,plan,chunk,original),permit).committed(actionKey);
            if(prior!=null)matches(prior,chunk,plan);return prior;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw unavailable();}
    }
    /** Reconstructs preparation progress from original permanent receipts, never from current result state. */
    public Progress progress(String intentId){
        try {
            JdbcSourceDedupResults.requireReadOnly();WireValues.id(intentId,false);var archived=plans.readImmutableOriginal(intentId);var plan=archived.plan();var original=archived.universe();partialProgressProof(plan);Long missing=null;
            for(var chunk:plan.chunks()){
                String action=SourcePlanPreparationCoordinator.partAction(intentId,chunk.stagePart());var prior=store(chunk,command(intentId,chunk.stagePart(),plan,chunk,original)).committed(action);
                if(prior==null){if(missing==null)missing=chunk.stagePart();}
                else {matches(prior,chunk,plan);if(missing!=null)throw unavailable();}
            }
            return new Progress(plan,missing==null?plan.chunks().size():missing);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    private GenerationJdbcStore store(SourcePartitionPlanner.Chunk chunk,Prepare command){return store(chunk,command,null);}
    private GenerationJdbcStore store(SourcePartitionPlanner.Chunk chunk,Prepare command,SourceDedupPartialDependencyPermitService.Captured permit) {
        return new GenerationJdbcStore(source,clock,actor,chunk.rootId(),command,collections,births,sourceDedup,permit);
    }
    private void partialProgressProof(SourcePartitionPlanner.Plan plan){
        if(plan.scope()==null||plan.scope().stageContextJson()==null)return;
        var context=CanonicalJson.parse(SourceValueStageContext.readAny(plan.scope().stageContextJson(),plan.scope().businessDate(),plan.scope().cutoff()));var own=context.get(FrozenSourceDedupDependency.FIELD);
        if(!"source-dedup-dependency-v2".equals(WireValues.text(own.get("schema_version"))))return;
        if(sourceDedup==null)throw unavailable();sourceDedup.readPartial(own,plan.scope().cutoff());
    }
    /** The complete plan was just read and validated under this writer's original locks. */
    private static void requireOriginalChunk(SourcePartitionPlanner.Plan plan,SourcePartitionPlanner.Chunk chunk){
        var snapshot=CanonicalJson.parse(chunk.snapshotJson());var partition=snapshot.get(SourcePartitionPlanner.FIELD);var fields=Set.of("schema_version","stage_part","universe_ref");WireValues.object(partition,fields,fields);
        long part=WireValues.nonnegativeInteger(partition.get("stage_part"));
        if(!"source-partition-v1".equals(WireValues.text(partition.get("schema_version")))||part>=10000||part>=plan.chunks().size()
                ||part!=chunk.stagePart()||!chunk.equals(plan.chunks().get((int)part))
                ||!plan.reference().equals(FrozenSourceUniverse.Reference.fromJson(JdbcFrozenInputs.canonical(partition.get("universe_ref"))))
                ||!SourcePartitionPlanner.rootId(plan.logicalKey(),plan.generation(),part).equals(chunk.rootId())
                ||!plan.logicalKey().equals(WireValues.text(snapshot.get("logical_key")))||plan.generation()!=WireValues.nonnegativeInteger(snapshot.get("generation"))
                ||!plan.stage().name().toLowerCase(Locale.ROOT).equals(WireValues.text(snapshot.get("stage")))
                ||!chunk.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot)))throw unavailable();
    }
    private static void partialProfile(JsonNode snapshot,SourceDedupPartialDependencyPermitService.Captured permit){
        if(permit==null)return;var profile=snapshot.get(FrozenSourceDedupDependency.FIELD);
        if(profile==null||!"source-dedup-dependency-v2".equals(WireValues.text(profile.get("schema_version")))||!permit.dependency().json().equals(JdbcFrozenInputs.canonical(profile)))throw unavailable();
    }
    private static void identity(String intentId,long stagePart,String actionKey) {
        WireValues.id(intentId,false);
        if(stagePart<0||stagePart>=10000||actionKey==null||!actionKey.matches("[A-Za-z0-9._:-]{1,128}"))throw unavailable();
    }
    private static Prepare command(String intentId,long stagePart,SourcePartitionPlanner.Plan plan,
            SourcePartitionPlanner.Chunk chunk,com.lianxibu.site.domain.sources.MemberUniverse.Universe original) {
        var snapshot=CanonicalJson.parse(chunk.snapshotJson());var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(!context.referencesComplete())throw unavailable();
        var dependencies=FrozenDependencyManifest.read(snapshot.get(FrozenDependencyManifest.FIELD));
        var proofs=snapshot.has(FrozenCollectionManifest.FIELD)?FrozenCollectionManifest.read(snapshot.get(FrozenCollectionManifest.FIELD)):List.<CollectionHandoffProofService.Proof>of();
        var command=new Prepare(plan.logicalKey(),plan.generation(),plan.stage(),plan.scope().businessDate(),original.sourceId(),
            dependencies.dependencies(),evidence(snapshot,plan.scope()),dependencies.expectedIds(),false,
            plan.scope().settings().semanticHash(),context,proofs,stagePart);
        CollectionInputProofs.validate(command);
        if(plan.stage().dedup()) {
            if(proofs.size()!=1)throw unavailable();var proof=proofs.getFirst();var facts=proof.facts();
            if(!intentId.equals(proof.intentId())||!original.runId().equals(facts.runId())||!original.manifestHash().equals(facts.manifestHash())
                    ||original.members().size()!=facts.expected()||original.accepted()!=facts.accepted()||original.excluded()!=facts.excluded())throw unavailable();
        }
        return command;
    }

    private static List<Evidence> evidence(JsonNode snapshot,SourcePartitionPlanner.Scope scope) {
        var members=snapshot.get("members");if(members==null||!members.isArray()||members.isEmpty()||members.size()>100)throw unavailable();
        var result=new ArrayList<Evidence>();
        for(var item:members) {
            var fields=Set.of("id","kind","business_date","completed_at","body_hash","body","references");WireValues.object(item,fields,fields);
            String id=WireValues.id(item.get("id"),false),kind=WireValues.text(item.get("kind"));
            LocalDate day=WireValues.businessDate(item.get("business_date"));String time=WireValues.text(item.get("completed_at"));Instant completed=Instant.parse(time);
            String hash=WireValues.text(item.get("body_hash"));
            if(kind.isBlank()||!time.endsWith("Z")||completed.getNano()%1000!=0||!scope.businessDate().equals(day)||completed.isAfter(scope.cutoff())
                    ||!hash.equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,item.get("body"))))throw unavailable();
            var refs=item.get("references");if(!refs.isArray()||refs.size()>10000)throw unavailable();var references=new ArrayList<String>();var seen=new HashSet<String>();
            for(var ref:refs){String reference=WireValues.id(ref,false);if(!seen.add(reference))throw unavailable();references.add(reference);}
            result.add(new Evidence(id,kind,day,completed,hash,JdbcFrozenInputs.canonical(item.get("body")),true,false,"accepted",List.copyOf(references)));
        }
        return List.copyOf(result);
    }
    private static List<String> strings(JsonNode node) {
        if(node==null||!node.isArray()||node.size()>10000)throw unavailable();var sorted=new TreeSet<String>();var values=new ArrayList<String>();
        for(var item:node){String value=WireValues.text(item);if(value.isBlank()||!sorted.add(value))throw unavailable();values.add(value);}
        if(!values.equals(new ArrayList<>(sorted)))throw unavailable();return List.copyOf(values);
    }
    private static void matches(Frozen value,SourcePartitionPlanner.Chunk chunk,SourcePartitionPlanner.Plan plan) {
        var snapshot=CanonicalJson.parse(chunk.snapshotJson());
        if(!chunk.rootId().equals(value.ownerId())||!plan.logicalKey().equals(value.logicalKey())||plan.generation()!=value.generation()
                ||plan.stage()!=value.stage()||!plan.scope().cutoff().equals(value.cutoff())||!"queued".equals(value.state())
                ||!WireValues.text(snapshot.get("coverage_state")).equals(value.coverageState())||!strings(snapshot.get("missing")).equals(value.missing())
                ||!chunk.snapshotJson().equals(value.snapshotJson())||!chunk.inputHash().equals(value.inputHash())||!chunk.requestJson().equals(value.requestJson()))throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("archived source stage preparation unavailable");}
}
