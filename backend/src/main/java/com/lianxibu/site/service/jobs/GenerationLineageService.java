package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Immutable replacement relations, including proof that generation one has no predecessor. */
public final class GenerationLineageService {
    public record Lineage(String rootId,long generation,String supersedesJobId) { }
    @FunctionalInterface public interface BirthSource {SourceStageBirthProof read(String rootId);}
    private final JdbcGenerationLineage store;private final Clock clock;private final String actor;private final BirthSource births;
    public GenerationLineageService(JdbcGenerationLineage store,Clock clock,String actor){this(store,clock,actor,null);}
    public GenerationLineageService(JdbcGenerationLineage store,Clock clock,String actor,BirthSource births){this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);this.actor=WireValues.id(actor,false);this.births=births;}
    public Lineage record(String id) {
        JdbcFrozenInputs.requireTx();var root=required(id);
        if(store.receipt(receiptId(id),action(id))!=null)return read(id);
        if(!"waiting_input".equals(root.state())||root.calls()!=0||root.previous()!=null)throw unavailable();
        var branch=store.branch(root.keyHash(),root.part());if(branch.size()>10000)throw unavailable();
        String previous=null;Set<Long> generations=new HashSet<>();
        for(var candidate:branch){validate(candidate);if(!root.logicalKey().equals(candidate.logicalKey())||!root.type().equals(candidate.type())||!generations.add(candidate.generation())||candidate.generation()>root.generation())throw unavailable();if(candidate.generation()==root.generation()-1)previous=candidate.id();}
        SourceStageBirthProof born=null;
        if(root.generation()==1){if(previous!=null)throw unavailable();}
        else if(previous!=null)read(previous);
        else born=birth(root);
        var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version",born==null?"generation-lineage-v1":"generation-lineage-v2");payload.put("root_id",id);payload.put("logical_key",root.logicalKey());payload.put("generation",root.generation());payload.put("stage_part",root.part());payload.put("task_type",root.type());payload.put("supersedes_job_id",previous);
        if(born!=null)payload.set("source_stage_birth",CanonicalJson.parse(born.json()));
        var target=JsonMapper.shared().createObjectNode();target.put("kind","logical_task");target.put("id",id);
        store.append(root,previous,new JdbcJobRecords.Disposition(receiptId(id),action(id),"generation_lineage",JdbcFrozenInputs.canonical(target),actor,"Frozen generation replacement relation",JdbcFrozenInputs.canonical(payload)),clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return read(id);
    }
    public Lineage read(String id) {
        var first=required(id);var current=first;int count=0;
        while(true){if(++count>10000)throw unavailable();var receipt=store.receipt(receiptId(current.id()),action(current.id()));if(receipt==null||!"generation_lineage".equals(receipt.targetType()))throw unavailable();WireValues.id(receipt.operatorId(),false);
            var target=CanonicalJson.parse(receipt.targetKeyJson());closed(target,"kind","id");if(!"logical_task".equals(WireValues.text(target.get("kind")))||!current.id().equals(WireValues.id(target.get("id"),false)))throw unavailable();
            var payload=CanonicalJson.parse(receipt.payloadJson());String version=WireValues.text(payload.get("schema_version"));boolean born="generation-lineage-v2".equals(version);
            if(!born&&!"generation-lineage-v1".equals(version))throw unavailable();
            closed(payload,born?new String[]{"schema_version","root_id","logical_key","generation","stage_part","task_type","supersedes_job_id","source_stage_birth"}:new String[]{"schema_version","root_id","logical_key","generation","stage_part","task_type","supersedes_job_id"});
            String previous=payload.get("supersedes_job_id").isNull()?null:WireValues.id(payload.get("supersedes_job_id"),false);
            if(!current.id().equals(WireValues.id(payload.get("root_id"),false))||!current.logicalKey().equals(WireValues.text(payload.get("logical_key")))||current.generation()!=WireValues.nonnegativeInteger(payload.get("generation"))||current.part()!=WireValues.nonnegativeInteger(payload.get("stage_part"))||!current.type().equals(WireValues.text(payload.get("task_type")))||!Objects.equals(current.previous(),previous))throw unavailable();
            if(born){if(current.generation()<2||previous!=null||!SourceStageBirthProof.fromJson(payload.get("source_stage_birth")).equals(birth(current)))throw unavailable();break;}
            if(current.generation()==1){if(previous!=null)throw unavailable();break;}if(previous==null)throw unavailable();
            var parent=required(previous);if(!first.logicalKey().equals(parent.logicalKey())||!first.type().equals(parent.type())||first.part()!=parent.part()||parent.generation()!=current.generation()-1)throw unavailable();current=parent;
        }
        return new Lineage(first.id(),first.generation(),first.previous());
    }
    private SourceStageBirthProof birth(JdbcGenerationLineage.Root root){
        if(births==null)throw unavailable();var proof=births.read(root.id());
        if(proof==null||!root.id().equals(proof.rootId())||!root.logicalKey().equals(proof.logicalKey())||root.generation()!=proof.generation()||root.part()!=proof.stagePart()
                ||!root.type().equals(proof.stage().name().toLowerCase(Locale.ROOT)))throw unavailable();return proof;
    }
    private JdbcGenerationLineage.Root required(String id){WireValues.id(id,false);var root=store.root(id);if(root==null||!id.equals(root.id()))throw unavailable();validate(root);return root;}
    private static void validate(JdbcGenerationLineage.Root root){if(root.logicalKey()==null||root.logicalKey().isBlank()||!Arrays.equals(root.keyHash(),JdbcGenerationLineage.hash(root.logicalKey()))||root.generation()<1||root.generation()>CanonicalJson.MAX_SAFE_INTEGER||root.part()<0||root.part()>CanonicalJson.MAX_SAFE_INTEGER)throw unavailable();WireValues.id(root.id(),false);WireValues.id(root.type(),false);}
    private static String action(String id){return "lineage:"+id;}
    private static String receiptId(String id){return JdbcFrozenInputs.stableId("lineage",id);}
    private static void closed(JsonNode node,String... names){var fields=Set.of(names);WireValues.object(node,fields,fields);}
    private static IllegalStateException unavailable(){return new IllegalStateException("generation lineage proof unavailable");}
}
