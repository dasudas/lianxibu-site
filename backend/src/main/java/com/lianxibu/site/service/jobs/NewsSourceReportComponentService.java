package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.articles.NewsReportLeafEvidenceService;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.MissingInput;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** One original source, native leaves and candidate mapping in one RR snapshot. Whole-day authority is separate. */
public final class NewsSourceReportComponentService {
    /** Full original applied P2 member facts, including members absent from source Top5. */
    public record EntityLeaf(String itemId,String entityId,String sourceId,String runId,String sourceItemId,LocalDate day,String rootId,String inputHash,long inputRevision,String bodyHash,String bodyJson,String title,String summary,String referenceJson,long validityRevision,boolean effectiveValid,boolean blocked,Instant availableAt){@Override public String toString(){return "OriginalNewsEntityReportLeaf[redacted]";}}
    public static final class Captured {
        private final Object issuer;private final FrozenSourceStagePlan.Reference reference;private final Instant cutoff;
        private final SourceValueReportCompletionService.Completed completed;private final Map<String,NewsReportLeafEvidenceService.Verified> leaves;
        private final NewsSourceReportCandidates.Value value;private final Map<String,List<String>> targets;private final List<EntityLeaf> entityLeaves;
        private Captured(Object issuer,FrozenSourceStagePlan.Reference reference,Instant cutoff,SourceValueReportCompletionService.Completed completed,Map<String,NewsReportLeafEvidenceService.Verified> leaves,NewsSourceReportCandidates.Value value,Map<String,List<String>> targets,List<EntityLeaf> entityLeaves){this.issuer=issuer;this.reference=reference;this.cutoff=cutoff;this.completed=completed;this.leaves=Map.copyOf(leaves);this.value=value;var copy=new LinkedHashMap<String,List<String>>();targets.forEach((id,refs)->copy.put(id,List.copyOf(refs)));this.targets=Map.copyOf(copy);this.entityLeaves=List.copyOf(entityLeaves);}
        public FrozenSourceStagePlan.Reference reference(){return reference;}public Instant cutoff(){return cutoff;}
        public String sourceId(){return completed.finalResult().receipt().sourceId();}public String runId(){return completed.finalResult().receipt().runId();}public LocalDate day(){return completed.finalResult().receipt().businessDate();}
        public List<EntityLeaf> entityLeaves(){return entityLeaves;}
        public NewsSourceReportCandidates.Value value(){return value;}public Map<String,List<String>> referenceTargetsByEvidence(){return targets;}
        /** Conservative retained original byte budget for a complete day consumer; never truncates membership. */
        public long originalByteSize(){
            long bytes=bytes(completed.stage().json())+bytes(completed.finalResult().result().json())+bytes(completed.plan().scope().stageContextJson());
            for(var chunk:completed.plan().chunks())bytes=Math.addExact(bytes,bytes(chunk.snapshotJson())+bytes(chunk.requestJson()));
            for(var candidate:value.candidates())bytes=Math.addExact(bytes,bytes(candidate.evidence().bodyJson()));for(var leaf:entityLeaves)bytes=Math.addExact(bytes,bytes(leaf.bodyJson())+bytes(leaf.referenceJson()));return bytes;
        }
        private static long bytes(String value){return value==null?0:value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
        public boolean successfulOriginalEmpty(){return completed.plan().decisions().isEmpty()&&completed.finalResult().result().missing().isEmpty()&&value.missing().isEmpty();}
        @Override public String toString(){return "OriginalNewsReportComponent[redacted]";}
    }
    private final Object issuer=new Object();private final SourceValueReportCompletionService completions;private final NewsReportLeafEvidenceService leaves;private final TransactionTemplate reads;
    public NewsSourceReportComponentService(SourceValueReportCompletionService completions,NewsReportLeafEvidenceService leaves,PlatformTransactionManager manager){this.completions=Objects.requireNonNull(completions);this.leaves=Objects.requireNonNull(leaves);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public Captured capture(FrozenSourceStagePlan.Reference reference,Instant cutoff){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();return reads.execute(s->read(reference,cutoff));}
    public Captured verifyObserved(Captured captured){
        readonly();if(captured==null||captured.issuer!=issuer)throw invalid();var fresh=read(captured.reference,captured.cutoff);
        if(fresh==null||!fresh.leaves.equals(captured.leaves)||!fresh.value.equals(captured.value)||!fresh.targets.equals(captured.targets)||!fresh.entityLeaves.equals(captured.entityLeaves)||!fresh.completed.stage().json().equals(captured.completed.stage().json())||!fresh.completed.finalResult().result().json().equals(captured.completed.finalResult().result().json())||!fresh.completed.availableAt().equals(captured.completed.availableAt()))throw invalid();return captured;
    }
    private Captured read(FrozenSourceStagePlan.Reference reference,Instant cutoff){
        readonly();var completed=completions.read(reference,cutoff);if(completed==null)return null;
        var verified=new LinkedHashMap<String,NewsReportLeafEvidenceService.Verified>();var eligibility=new LinkedHashMap<String,NewsSourceReportCandidates.Eligibility>();var missing=new ArrayList<MissingInput>();var itemRoots=new HashMap<String,String>();var entityLeaves=new ArrayList<EntityLeaf>();var memberFacts=new HashMap<String,com.lianxibu.site.service.sources.SourceMemberStageService.Fact>();for(var fact:completed.stage().members())if(memberFacts.putIfAbsent(fact.itemId(),fact)!=null)throw invalid();
        for(var fragment:completed.stage().fragments()){
            var chunk=completed.plan().chunks().stream().filter(c->c.rootId().equals(fragment.rootId())).findFirst().orElseThrow(NewsSourceReportComponentService::invalid);var snapshot=CanonicalJson.parse(chunk.snapshotJson());var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));var input=context.input(chunk.rootId(),"source-report-leaf-check",chunk.inputHash(),0);var bindings=FrozenMemberBindings.read(snapshot,input);
            for(var member:snapshot.get("members")){
                String id=WireValues.id(member.get("id"),false);var bound=bindings.get(id);if(bound==null||itemRoots.putIfAbsent(id,fragment.rootId())!=null)throw invalid();
                var original=new NewsReportLeafEvidenceService.Original(id,completed.finalResult().receipt().runId(),completed.finalResult().receipt().sourceId(),bound.expectedRevision(),WireValues.sha256(member.get("body_hash")),com.lianxibu.site.repository.jobs.JdbcFrozenInputs.canonical(member.get("body")));
                var proof=leaves.read(original,cutoff);if(proof==null)missing.add(new MissingInput("source_item",id,completed.plan().scope().businessDate(),"article_revision_unproven"));else{verified.put(id,proof);eligibility.put(id,new NewsSourceReportCandidates.Eligibility(proof.effectiveValid(),proof.blocked()));var fact=memberFacts.get(id);if(fact==null||!original.runId().equals(fact.runId())||!original.sourceId().equals(fact.sourceId())||!"news_tech".equals(fact.category())||!completed.plan().scope().businessDate().equals(fact.businessDate()))throw invalid();entityLeaves.add(new EntityLeaf(id,fact.entityId(),fact.sourceId(),fact.runId(),fact.sourceItemId(),fact.businessDate(),chunk.rootId(),chunk.inputHash(),bound.expectedRevision(),original.bodyHash(),original.bodyJson(),bound.title(),bound.summary(),proof.referenceJson(),proof.validityRevision(),proof.effectiveValid(),proof.blocked()||fact.entityId()==null,completed.availableAt()));}
            }
        }
        if(itemRoots.size()!=completed.stage().members().size())throw invalid();
        var targets=new LinkedHashMap<String,List<String>>();NewsSourceReportCandidates.Value value;
        if(!missing.isEmpty()){
            completed.finalResult().result().missing().forEach(m->missing.add(new MissingInput(m.kind(),m.id(),completed.plan().scope().businessDate(),m.reason())));
            missing.add(new MissingInput("source_summary",reference.ownerId(),completed.plan().scope().businessDate(),"source_leaf_unproven"));
            var dependencies=completed.stage().fragments().stream().map(f->new GenerationFlowService.Dependency(f.rootId(),GenerationFlowService.Stage.NEWS_VALUE,"succeeded",completed.availableAt())).toList();
            value=new NewsSourceReportCandidates.Value(List.of(),dependencies,missing,List.of());
        }else{
            value=NewsSourceReportCandidates.create(completed,cutoff,eligibility);
            for(var candidate:value.candidates()){
                List<String> refs;if(candidate.group()==ReportInputSnapshotService.Group.SOURCE_SUMMARIES)refs=verified.values().stream().map(NewsReportLeafEvidenceService.Verified::referenceJson).distinct().sorted().toList();else{var proof=verified.get(candidate.metadata().resourceRef().id());if(proof==null)throw invalid();refs=List.of(proof.referenceJson());}
                if(refs.isEmpty()||targets.putIfAbsent(candidate.evidence().id(),refs)!=null)throw invalid();
            }
        }
        entityLeaves.sort(Comparator.comparing(EntityLeaf::itemId));return new Captured(issuer,reference,cutoff,completed,verified,value,targets,missing.isEmpty()?entityLeaves:List.of());
    }
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original source report component unavailable");}
}
