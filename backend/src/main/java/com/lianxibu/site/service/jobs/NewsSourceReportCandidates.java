package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.time.*;
import java.util.*;

/** Pure mapping of original source output. Native leaf validity and full-day completeness are caller obligations. */
public final class NewsSourceReportCandidates {
    public record Eligibility(boolean effectiveValid,boolean blocked){ }
    public record LeafLocator(String itemId,String fragmentRoot,String inputHash,String originalBodyHash){ }
    public record Value(List<Candidate> candidates,List<Dependency> dependencies,List<MissingInput> missing,List<LeafLocator> leaves){
        public Value{candidates=List.copyOf(candidates);dependencies=List.copyOf(dependencies);missing=List.copyOf(missing);leaves=List.copyOf(leaves);}
        @Override public String toString(){return "OriginalNewsReportCandidates[redacted]";}
    }
    private NewsSourceReportCandidates(){ }
    public static Value create(SourceValueReportCompletionService.Completed completed,Instant cutoff,Map<String,Eligibility> eligibility){
        try{
            Objects.requireNonNull(completed);Objects.requireNonNull(eligibility);var plan=completed.plan();var stage=completed.stage();var finalProof=completed.finalResult();var result=finalProof.result();var receipt=finalProof.receipt();var body=CanonicalJson.parse(result.json());
            if(cutoff==null||cutoff.getNano()%1000!=0||completed.availableAt().getNano()%1000!=0||completed.availableAt().isAfter(cutoff)||completed.availableAt().isBefore(finalProof.minimumObservationTime())
                ||plan.stage()!=Stage.NEWS_VALUE||!plan.scope().businessDate().equals(receipt.businessDate())||plan.generation()!=receipt.position().generation()
                ||!plan.logicalKey().equals(receipt.position().logicalKey())||!stage.planRef().ownerId().equals(receipt.position().ownerId())
                ||!stage.planRef().json().equals(JdbcFrozenInputs.canonical(body.get("plan_ref")))||!stage.hash().equals(WireValues.sha256(body.get("whole_result_hash")))
                ||!result.hash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body))||!result.hash().equals(receipt.position().resultHash()))throw invalid();
            var originalMembers=new HashMap<String,com.lianxibu.site.service.sources.SourceMemberStageService.Fact>();
            for(var member:stage.members())if(originalMembers.putIfAbsent(member.itemId(),member)!=null)throw invalid();
            if(!eligibility.keySet().equals(originalMembers.keySet())||eligibility.values().stream().anyMatch(Objects::isNull))throw invalid();
            var chunks=new HashMap<String,SourcePartitionPlanner.Chunk>();for(var chunk:plan.chunks())if(chunks.putIfAbsent(chunk.rootId(),chunk)!=null)throw invalid();
            var raw=new HashMap<String,tools.jackson.databind.JsonNode>();var bindings=new HashMap<String,FrozenMemberBindings.Member>();var roots=new HashMap<String,String>();var leaves=new ArrayList<LeafLocator>();var dependencies=new ArrayList<Dependency>();
            for(var fragment:stage.fragments()){
                var chunk=chunks.get(fragment.rootId());if(chunk==null||!chunk.inputHash().equals(fragment.result().inputHash()))throw invalid();var snapshot=CanonicalJson.parse(chunk.snapshotJson());var complete=NewsValueInputProfile.complete(snapshot);var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));var input=context.input(chunk.rootId(),"report-candidate-check",chunk.inputHash(),0);var bound=FrozenMemberBindings.read(snapshot,input);
                for(var member:snapshot.get("members")){String id=WireValues.id(member.get("id"),false);var fact=originalMembers.get(id);var binding=bound.get(id);var payload=member.get("body");String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,payload);if(fact==null||binding==null||!chunk.itemIds().contains(id)||!hash.equals(WireValues.sha256(member.get("body_hash")))||!complete.containsKey(id)||!complete.get(id).originalBodyHash().equals(hash)||!input.eligibleHighValueItems().contains(id)&&result.selected().stream().anyMatch(v->v.itemId().equals(id))||raw.putIfAbsent(id,payload)!=null)throw invalid();bindings.put(id,binding);roots.put(id,fragment.rootId());leaves.add(new LeafLocator(id,fragment.rootId(),chunk.inputHash(),hash));}
                dependencies.add(new Dependency(fragment.rootId(),Stage.NEWS_VALUE,"succeeded",completed.availableAt()));
            }
            if(!raw.keySet().equals(originalMembers.keySet()))throw invalid();leaves.sort(Comparator.comparing(LeafLocator::itemId));
            var day=receipt.businessDate();var refs=dependencies.stream().map(Dependency::id).toList();String summaryId=JdbcFrozenInputs.stableId("report-source-summary",finalProof.observationKeyJson());
            boolean summaryValid=eligibility.values().stream().allMatch(Eligibility::effectiveValid),summaryBlocked=eligibility.values().stream().anyMatch(Eligibility::blocked);
            var summaryMeta=new Metadata(new ResourceRef("source_run",receipt.runId(),null),receipt.projectionRevision(),summaryId,result.hash(),receipt.sourceId(),receipt.runId(),null,day,null,result.summary(),null);
            var summary=new Evidence(summaryId,"source_summary",day,completed.availableAt(),result.hash(),result.json(),summaryValid,summaryBlocked,"accepted",refs);
            var candidates=new ArrayList<Candidate>();if(!stage.members().isEmpty())candidates.add(new Candidate(Group.SOURCE_SUMMARIES,summaryMeta,summary,"news_tech",false,false,null,null));
            var selected=new HashSet<String>();
            for(var item:result.selected()){
                if(!selected.add(item.itemId()))throw invalid();var payload=raw.get(item.itemId());var binding=bindings.get(item.itemId());var fact=originalMembers.get(item.itemId());String root=roots.get(item.itemId());if(payload==null||binding==null||fact==null||!root.equals(item.rootId())||!fact.entityId().equals(item.entityId()))throw invalid();String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,payload),id=JdbcFrozenInputs.stableId("report-source-item",root+":"+item.itemId()+":"+hash);var state=eligibility.get(item.itemId());
                var metadata=new Metadata(new ResourceRef("source_item",item.itemId(),null),binding.expectedRevision(),id,hash,receipt.sourceId(),receipt.runId(),fact.sourceItemId(),day,binding.title(),binding.summary(),null);
                var evidence=new Evidence(id,"source_member",day,completed.availableAt(),hash,JdbcFrozenInputs.canonical(payload),state.effectiveValid(),state.blocked(),"accepted",List.of(root));candidates.add(new Candidate(Group.HIGH_VALUE_ITEMS,metadata,evidence,"news_tech",true,false,null,null));
            }
            var missing=result.missing().stream().map(m->new MissingInput(m.kind(),m.id(),day,m.reason())).toList();return new Value(candidates,dependencies,missing,leaves);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original news report candidates unavailable");}
}
