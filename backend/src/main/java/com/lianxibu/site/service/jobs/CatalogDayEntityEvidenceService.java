package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;

/** Complete original bodies from native frozen source plans, bound to the original day finals. */
public final class CatalogDayEntityEvidenceService {
    public static final long MAX_BYTES=32L*1024*1024;
    public record Evidence(CatalogDayEntityGrouping.Member member,String category,String identityKey,String title,String summary,
                           long originalRevision,String inputHash,String bodyHash,String bodyJson,String referencesJson,Instant completedAt){
        @Override public String toString(){return "OriginalCatalogMemberEvidence[redacted]";}
    }
    public record Entity(String entityId,String category,List<Evidence> members){public Entity{members=List.copyOf(members);}
        @Override public String toString(){return "OriginalCatalogEntityEvidence[redacted]";}}
    public static final class Captured {
        private final Object issuer;private final CatalogDayOriginalFinalsService.Captured original;
        private final CatalogDayEntityGrouping.Plan grouping;private final List<Entity> entities;private final long originalBytes;
        private Captured(Object issuer,CatalogDayOriginalFinalsService.Captured original,CatalogDayEntityGrouping.Plan grouping,List<Entity> entities,long originalBytes){this.issuer=issuer;this.original=original;this.grouping=grouping;this.entities=List.copyOf(entities);this.originalBytes=originalBytes;}
        public CatalogDayOriginalFinalsService.Captured original(){return original;}public CatalogDayEntityGrouping.Plan grouping(){return grouping;}
        public List<Entity> entities(){return entities;}public long originalByteSize(){return originalBytes;}
        @Override public String toString(){return "OriginalCatalogDayEvidence[redacted]";}
    }
    private final Object issuer=new Object();private final String category;private final CatalogDayOriginalFinalsService finals;
    private final FrozenSourceStagePlanService plans;private final TransactionTemplate reads;
    public CatalogDayEntityEvidenceService(CatalogDayOriginalFinalsService finals,FrozenSourceStagePlanService plans,PlatformTransactionManager manager,String category){
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};this.finals=Objects.requireNonNull(finals);this.plans=Objects.requireNonNull(plans);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Captured capture(LocalDate day){outside();var original=finals.capture(day);return original==null?null:read(original);}
    public Captured captureOriginal(LocalDate day){outside();var original=finals.captureOriginal(day);return original==null?null:read(original);}
    private Captured read(CatalogDayOriginalFinalsService.Captured original){return reads.execute(s->{finals.verifyObserved(original);var grouping=CatalogDayEntityGrouping.group(original);var full=evidence(original,grouping);return new Captured(issuer,original,grouping,full.entities(),full.bytes());});}
    /** Rechecks all original native proofs in one borrowed readonly RR snapshot. No current-data authority. */
    public Captured verifyObserved(Captured captured){
        readonly();if(captured==null||captured.issuer!=issuer||!category.equals(captured.original.category()))throw invalid();
        finals.verifyObserved(captured.original);var grouping=CatalogDayEntityGrouping.group(captured.original);
        var full=evidence(captured.original,grouping);if(!grouping.json().equals(captured.grouping.json())||!full.entities().equals(captured.entities)||full.bytes()!=captured.originalBytes)throw invalid();return captured;
    }
    private record EvidenceRead(List<Entity> entities,long bytes){ }
    private EvidenceRead evidence(CatalogDayOriginalFinalsService.Captured original,CatalogDayEntityGrouping.Plan grouping){
        readonly();if(!category.equals(original.category()))throw invalid();
        var anchors=new HashMap<String,CatalogDayEntityGrouping.Member>();for(var group:grouping.groups())for(var member:group.members())if(anchors.putIfAbsent(member.itemId(),member)!=null)throw invalid();
        var found=new HashMap<String,Evidence>();long bytes=grouping.json().getBytes(StandardCharsets.UTF_8).length;bytes=Math.addExact(bytes,original.closed().original().plan().bundleJson().getBytes(StandardCharsets.UTF_8).length);bytes=Math.addExact(bytes,original.closed().closure().json().getBytes(StandardCharsets.UTF_8).length);
        for(var committed:original.sources()){
            bytes=Math.addExact(bytes,committed.result().json().getBytes(StandardCharsets.UTF_8).length+committed.receipt().json().getBytes(StandardCharsets.UTF_8).length);
            var result=CanonicalJson.parse(committed.result().json());var reference=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(result.get("plan_ref")));var plan=plans.readImmutable(reference);
            if(!category.equals(committed.receipt().category())||!original.day().equals(plan.scope().businessDate())||plan.generation()!=1||!plan.stage().name().equals(category.toUpperCase(Locale.ROOT)+"_DEDUP")||plan.scope().cutoff().isAfter(original.cutoff()))throw invalid();
            bytes=Math.addExact(bytes,plan.scope().stageContextJson()==null?0:plan.scope().stageContextJson().getBytes(StandardCharsets.UTF_8).length);var chunks=new HashMap<String,SourcePartitionPlanner.Chunk>();for(var chunk:plan.chunks()){
                bytes=Math.addExact(bytes,(long)chunk.snapshotJson().getBytes(StandardCharsets.UTF_8).length+chunk.requestJson().getBytes(StandardCharsets.UTF_8).length);if(bytes>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
                if(chunks.putIfAbsent(chunk.rootId(),chunk)!=null)throw invalid();
            }
            for(var fragment:result.get("stage_result").get("fragments")){
                String root=WireValues.id(fragment.get("root_id"),false);var chunk=chunks.remove(root);
                if(chunk==null||chunk.stagePart()!=WireValues.nonnegativeInteger(fragment.get("stage_part"))||!chunk.inputHash().equals(WireValues.sha256(fragment.get("input_hash"))))throw invalid();
                var snapshot=CanonicalJson.parse(chunk.snapshotJson());var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
                if(context.stage()!=plan.stage()||context.generation()!=1||!original.day().equals(WireValues.businessDate(snapshot.get("business_date")))||!plan.scope().cutoff().equals(Instant.parse(WireValues.text(snapshot.get("cutoff"))))||!committed.receipt().sourceId().equals(WireValues.id(snapshot.get("source_id"),true)))throw invalid();
                var bindings=FrozenMemberBindings.read(snapshot,context.input(root,"original-day-evidence",chunk.inputHash(),0));
                var bodies=new HashMap<String,JsonNode>();var members=snapshot.get("members");if(members==null||!members.isArray())throw invalid();
                for(var member:members){WireValues.object(member,Set.of("id","kind","business_date","completed_at","body_hash","body","references"),Set.of("id","kind","business_date","completed_at","body_hash","body","references"));if(bodies.putIfAbsent(WireValues.id(member.get("id"),false),member)!=null)throw invalid();}
                if(!bodies.keySet().equals(bindings.keySet())||!bindings.keySet().equals(Set.copyOf(chunk.itemIds())))throw invalid();var applied=new HashSet<String>();
                for(var fact:fragment.get("members")){
                    String id=WireValues.id(fact.get("itemId"),false);var binding=bindings.get(id);var member=bodies.get(id);if(binding==null||!applied.add(id))throw invalid();
                    Instant completed=WireValues.utcMicroseconds(member.get("completed_at"));String hash=WireValues.sha256(member.get("body_hash"));
                    if(binding.expectedRevision()>CanonicalJson.MAX_SAFE_INTEGER-2||WireValues.nonnegativeInteger(fact.get("revision"))!=binding.expectedRevision()+2||!category.equals(binding.category())||!"source_member".equals(WireValues.text(member.get("kind")))||!original.day().equals(WireValues.businessDate(member.get("business_date")))||completed.isAfter(plan.scope().cutoff())||!hash.equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,member.get("body")))||!member.get("references").isArray())throw invalid();
                    var anchor=anchors.get(id);if(anchor!=null){if(!root.equals(anchor.rootId())||!anchor.runId().equals(committed.receipt().runId())||!anchor.sourceId().equals(committed.receipt().sourceId())||!anchor.finalResultHash().equals(committed.result().hash())||!anchor.fragmentResultHash().equals(WireValues.sha256(fragment.get("result_hash")))||anchor.revision()!=WireValues.nonnegativeInteger(fact.get("revision")))throw invalid();
                        var proof=new Evidence(anchor,category,binding.identityKey(),binding.title(),binding.summary(),binding.expectedRevision(),chunk.inputHash(),hash,JdbcFrozenInputs.canonical(member.get("body")),JdbcFrozenInputs.canonical(member.get("references")),completed);
                        if(found.putIfAbsent(id,proof)!=null)throw invalid();
                    }
                }
                if(!applied.equals(bindings.keySet()))throw invalid();
            }
        }
        if(!found.keySet().equals(anchors.keySet()))throw invalid();var entities=new ArrayList<Entity>();for(var group:grouping.groups()){var members=group.members().stream().map(m->found.get(m.itemId())).toList();entities.add(new Entity(group.entityId(),category,members));}if(bytes>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();return new EvidenceRead(List.copyOf(entities),bytes);
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("complete original catalog entity evidence unavailable");}
}
