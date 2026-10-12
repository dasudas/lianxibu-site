package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;

/** Resolves current original fragment facts. A later seal must recheck every native revision. */
public final class SourceStageResolutionService {
    public enum State { MISSING,PENDING,APPLIED,FAILED,CANCELLED,DISCARDED,SUPERSEDED }
    public static final class Fragment {
        private final SourcePartitionPlanner.Chunk chunk;private final State state;private final JdbcSourceStageRoots.Native nativeRoot;
        private final SourceDedupResultProofService.Applied dedup;private final SourceValueResultProofService.Applied value;
        private Fragment(SourcePartitionPlanner.Chunk chunk,State state,JdbcSourceStageRoots.Native nativeRoot,
                SourceDedupResultProofService.Applied dedup,SourceValueResultProofService.Applied value){
            this.chunk=chunk;this.state=state;this.nativeRoot=nativeRoot;this.dedup=dedup;this.value=value;
        }
        public SourcePartitionPlanner.Chunk chunk(){return chunk;}public State state(){return state;}public JdbcSourceStageRoots.Native nativeRoot(){return nativeRoot;}
        public SourceDedupResultProofService.Applied dedup(){return dedup;}public SourceValueResultProofService.Applied value(){return value;}
        @Override public String toString(){return "SourceStageResolvedFragment[redacted]";}
    }
    public static final class Snapshot {
        private final SourcePartitionPlanner.Plan plan;private final MemberUniverse.Universe universe;private final FrozenSourceStagePlan.Reference reference;private final List<Fragment> fragments;
        private Snapshot(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,FrozenSourceStagePlan.Reference reference,List<Fragment> fragments){this.plan=plan;this.universe=universe;this.reference=reference;this.fragments=List.copyOf(fragments);}
        public SourcePartitionPlanner.Plan plan(){return plan;}public MemberUniverse.Universe universe(){return universe;}public FrozenSourceStagePlan.Reference reference(){return reference;}public List<Fragment> fragments(){return fragments;}
        public boolean settled(){return fragments.stream().noneMatch(f->f.state()==State.MISSING||f.state()==State.PENDING);}
        public boolean hasFailures(){return fragments.stream().anyMatch(f->f.state()!=State.APPLIED&&f.state()!=State.MISSING&&f.state()!=State.PENDING);}
        @Override public String toString(){return "SourceStageResolution[redacted]";}
    }
    private final JdbcSourceStageRoots roots;private final FrozenSourceStagePlanService plans;private final FrozenSourceUniverseService universes;
    private final SourceStagePreparationService preparations;private final JdbcFrozenInputs inputs;private final JdbcSourceDedupResults results;
    private final SourceDedupResultProofService dedup;private final SourceValueResultProofService value;private final SourceDedupDependencyService dependencies;
    public SourceStageResolutionService(JdbcSourceStageRoots roots,FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,
            SourceStagePreparationService preparations,JdbcFrozenInputs inputs,JdbcSourceDedupResults results,
            SourceDedupResultProofService dedup,SourceValueResultProofService value,SourceDedupDependencyService dependencies){
        this.roots=Objects.requireNonNull(roots);this.plans=Objects.requireNonNull(plans);this.universes=Objects.requireNonNull(universes);
        this.preparations=Objects.requireNonNull(preparations);this.inputs=Objects.requireNonNull(inputs);this.results=Objects.requireNonNull(results);
        this.dedup=Objects.requireNonNull(dedup);this.value=Objects.requireNonNull(value);this.dependencies=Objects.requireNonNull(dependencies);
    }
    public Snapshot read(FrozenSourceStagePlan.Reference reference){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);var plan=plans.readImmutable(reference);var universe=universes.readImmutable(plan.reference());
            if(!plan.stage().dedup()&&plan.stage()!=GenerationFlowService.Stage.NEWS_VALUE||plan.scope()==null)throw invalid();
            if(plan.stage()==GenerationFlowService.Stage.NEWS_VALUE){var context=CanonicalJson.parse(SourceValueStageContext.readAny(plan.scope().stageContextJson(),plan.scope().businessDate(),plan.scope().cutoff()));var own=context.get(FrozenSourceDedupDependency.FIELD);if("source-dedup-dependency-v2".equals(own.get("schema_version").asText()))dependencies.readPartial(own,plan.scope().cutoff());else dependencies.read(own,plan.scope().cutoff());}
            var captured=new LinkedHashMap<String,JdbcSourceStageRoots.Native>();var appliedIds=new ArrayList<String>();
            for(var chunk:plan.chunks()){var nativeRoot=roots.find(chunk.rootId());captured.put(chunk.rootId(),nativeRoot);if(nativeRoot!=null&&"succeeded".equals(nativeRoot.state()))appliedIds.add(chunk.rootId());}
            Map<String,SourceDedupResultProofService.Applied> applied=plan.stage().dedup()&&!appliedIds.isEmpty()?dedup.readAppliedBatch(reference,appliedIds):Map.of();
            Map<String,SourceValueResultProofService.Applied> valued=plan.stage()==GenerationFlowService.Stage.NEWS_VALUE&&!appliedIds.isEmpty()?value.readAppliedBatch(reference,appliedIds):Map.of();
            var fragments=new ArrayList<Fragment>();for(var chunk:plan.chunks())fragments.add(resolve(plan,chunk,captured.get(chunk.rootId()),applied,valued));
            // READ_COMMITTED capture only. Durable consumers still need an atomic revision recheck.
            for(var fragment:fragments)if(!Objects.equals(fragment.nativeRoot(),roots.find(fragment.chunk().rootId())))throw invalid();
            return new Snapshot(plan,universe,reference,fragments);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Fragment resolve(SourcePartitionPlanner.Plan plan,SourcePartitionPlanner.Chunk chunk,JdbcSourceStageRoots.Native nativeRoot,Map<String,SourceDedupResultProofService.Applied> applied,Map<String,SourceValueResultProofService.Applied> valued){
        if(nativeRoot==null)return new Fragment(chunk,State.MISSING,null,null,null);
        if(!chunk.rootId().equals(nativeRoot.id())||!plan.reference().ownerId().equals(nativeRoot.intent())||!plan.logicalKey().equals(nativeRoot.key())
                ||plan.generation()!=nativeRoot.generation()||chunk.stagePart()!=nativeRoot.part()||!plan.stage().name().toLowerCase(Locale.ROOT).equals(nativeRoot.type())
                ||!plan.scope().businessDate().equals(nativeRoot.day())||!plan.scope().cutoff().equals(nativeRoot.cutoff())||!chunk.inputHash().equals(nativeRoot.inputHash())||!"ready".equals(nativeRoot.inputState())
                ||!chunk.snapshotJson().equals(inputs.read(chunk.rootId(),chunk.inputHash())))throw invalid();
        if("succeeded".equals(nativeRoot.state())){
            if(!"APPLIED".equals(nativeRoot.application())||nativeRoot.appliedAt()==null||nativeRoot.resultHash()==null)throw invalid();
            if(plan.stage().dedup()){var proof=applied.get(chunk.rootId());if(proof==null||!nativeRoot.appliedAt().equals(proof.appliedAt())||!nativeRoot.resultHash().equals(proof.proof().result().resultHash()))throw invalid();return new Fragment(chunk,State.APPLIED,nativeRoot,proof,null);}
            var proof=valued.get(chunk.rootId());if(proof==null||!nativeRoot.appliedAt().equals(proof.appliedAt())||!nativeRoot.resultHash().equals(proof.proof().result().resultHash()))throw invalid();return new Fragment(chunk,State.APPLIED,nativeRoot,null,proof);
        }
        var prepared=preparations.committedPart(plan.reference().ownerId(),chunk.stagePart(),SourcePlanPreparationCoordinator.partAction(plan.reference().ownerId(),chunk.stagePart()));
        if(prepared==null||!chunk.snapshotJson().equals(prepared.snapshotJson())||!chunk.requestJson().equals(prepared.requestJson())||!chunk.inputHash().equals(prepared.inputHash())
                ||nativeRoot.appliedAt()!=null||"APPLIED".equals(nativeRoot.application())||results.handoff(chunk.rootId())!=null)throw invalid();
        if(nativeRoot.application()!=null&&nativeRoot.resultHash()==null)throw invalid();
        if(nativeRoot.resultHash()!=null){
            var stored=results.root(chunk.rootId());if(!nativeRoot.resultHash().equals(stored.resultHash()))throw invalid();
            var context=FrozenApplicationContext.fromJson(CanonicalJson.parse(chunk.snapshotJson()).get(FrozenApplicationContext.FIELD));
            // Validation does not apply the result or grant an attempt permission to send.
            var input=context.input(chunk.rootId(),JdbcFrozenInputs.stableId("attempt",chunk.rootId()+":1"),chunk.inputHash(),nativeRoot.revision());
            if(!nativeRoot.resultHash().equals(ResultApplicationService.validate(input,results.retained(stored)).resultHash()))throw invalid();
        }
        State state=switch(nativeRoot.state()){
            case "failed"->{if(nativeRoot.calls()!=3||nativeRoot.application()!=null||nativeRoot.resultHash()!=null)throw invalid();yield State.FAILED;}
            case "cancelled"->{if(nativeRoot.application()!=null||nativeRoot.resultHash()!=null)throw invalid();yield State.CANCELLED;}
            case "discarded"->{terminalResult(nativeRoot,"DISCARDED");yield State.DISCARDED;}
            case "superseded"->{terminalResult(nativeRoot,"SUPERSEDED");yield State.SUPERSEDED;}
            default->{
                if(nativeRoot.application()!=null&&!(("result_conflict".equals(nativeRoot.state())&&"CONTENT_REVISION_CONFLICT".equals(nativeRoot.application()))
                        ||("blocked".equals(nativeRoot.state())&&"INVALID_EVIDENCE".equals(nativeRoot.application()))))throw invalid();
                if("result_conflict".equals(nativeRoot.state())&&nativeRoot.application()==null)throw invalid();yield State.PENDING;
            }
        };
        return new Fragment(chunk,state,nativeRoot,null,null);
    }
    private static void terminalResult(JdbcSourceStageRoots.Native root,String application){
        if(root.resultHash()==null?root.application()!=null:!application.equals(root.application()))throw invalid();
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original source fragment resolution unavailable");}
}
