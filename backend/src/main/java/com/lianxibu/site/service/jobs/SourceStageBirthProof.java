package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure original-plan relation. Only a trusted archive reader can grant this proof. */
public record SourceStageBirthProof(String rootId,String logicalKey,long generation,long stagePart,Stage stage,
        LocalDate businessDate,FrozenSourceStagePlan.Reference currentPlanRef,
        FrozenSourceStagePlan.Reference previousPlanRef,long previousStagePartCount) {
    private static final String VERSION="source-stage-birth-v1";
    private static final Set<String> FIELDS=Set.of("schema_version","root_id","logical_key","generation","stage_part","task_type",
            "business_date","current_plan_ref","previous_plan_ref","previous_stage_part_count");

    public SourceStageBirthProof {
        WireValues.id(rootId,false);
        if(logicalKey==null||logicalKey.isBlank()||logicalKey.getBytes(StandardCharsets.UTF_8).length>1024
                ||generation<2||generation>CanonicalJson.MAX_SAFE_INTEGER||stagePart<0||stagePart>=10000
                ||previousStagePartCount<0||previousStagePartCount>stagePart||stage==null||!(stage.dedup()||stage==Stage.NEWS_VALUE)
                ||businessDate==null||currentPlanRef==null||previousPlanRef==null
                ||!rootId.equals(SourcePartitionPlanner.rootId(logicalKey,generation,stagePart))
                ||!owner(logicalKey,generation).equals(currentPlanRef.ownerId())||!owner(logicalKey,generation-1).equals(previousPlanRef.ownerId()))throw invalid();
    }

    public static SourceStageBirthProof create(String rootId,SourcePartitionPlanner.Plan current,FrozenSourceStagePlan.Reference currentRef,
            SourcePartitionPlanner.Plan previous,FrozenSourceStagePlan.Reference previousRef) {
        try {
            validate(current);validate(previous);Objects.requireNonNull(currentRef);Objects.requireNonNull(previousRef);
            if(!current.reference().ownerId().equals(currentRef.ownerId())||!previous.reference().ownerId().equals(previousRef.ownerId())
                    ||!current.logicalKey().equals(previous.logicalKey())||current.stage()!=previous.stage()||current.generation()!=previous.generation()+1
                    ||!current.scope().businessDate().equals(previous.scope().businessDate())||current.scope().cutoff().isBefore(previous.scope().cutoff()))throw invalid();
            var selected=current.chunks().stream().filter(c->rootId.equals(c.rootId())).toList();if(selected.size()!=1)throw invalid();
            var chunk=selected.getFirst();if(chunk.stagePart()<previous.chunks().size())throw invalid();
            return new SourceStageBirthProof(rootId,current.logicalKey(),current.generation(),chunk.stagePart(),current.stage(),current.scope().businessDate(),currentRef,previousRef,previous.chunks().size());
        }catch(RuntimeException rejected){throw invalid();}
    }

    public String json(){
        var n=JsonMapper.shared().createObjectNode();n.put("schema_version",VERSION);n.put("root_id",rootId);n.put("logical_key",logicalKey);
        n.put("generation",generation);n.put("stage_part",stagePart);n.put("task_type",stage.name().toLowerCase(Locale.ROOT));n.put("business_date",businessDate.toString());
        n.set("current_plan_ref",CanonicalJson.parse(currentPlanRef.json()));n.set("previous_plan_ref",CanonicalJson.parse(previousPlanRef.json()));n.put("previous_stage_part_count",previousStagePartCount);
        byte[] bytes=CanonicalJson.bytes(n);if(bytes.length>4096)throw invalid();return new String(bytes,StandardCharsets.UTF_8);
    }
    public static SourceStageBirthProof fromJson(JsonNode n){
        try {
            WireValues.object(n,FIELDS,FIELDS);if(CanonicalJson.bytes(n).length>4096||!VERSION.equals(WireValues.text(n.get("schema_version"))))throw invalid();
            String raw=WireValues.text(n.get("task_type"));Stage stage=Stage.valueOf(raw.toUpperCase(Locale.ROOT));if(!raw.equals(stage.name().toLowerCase(Locale.ROOT)))throw invalid();
            return new SourceStageBirthProof(WireValues.id(n.get("root_id"),false),WireValues.text(n.get("logical_key")),WireValues.nonnegativeInteger(n.get("generation")),
                    WireValues.nonnegativeInteger(n.get("stage_part")),stage,WireValues.businessDate(n.get("business_date")),
                    FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(n.get("current_plan_ref"))),
                    FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(n.get("previous_plan_ref"))),WireValues.nonnegativeInteger(n.get("previous_stage_part_count")));
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static void validate(SourcePartitionPlanner.Plan plan){
        Objects.requireNonNull(plan);
        if(plan.logicalKey()==null||plan.logicalKey().isBlank()||plan.generation()<1||plan.generation()>CanonicalJson.MAX_SAFE_INTEGER||plan.stage()==null
                ||!(plan.stage().dedup()||plan.stage()==Stage.NEWS_VALUE)||plan.scope()==null||plan.reference()==null||plan.chunks().size()>10000
                ||!owner(plan.logicalKey(),plan.generation()).equals(plan.reference().ownerId()))throw invalid();
        for(int n=0;n<plan.chunks().size();n++){var chunk=plan.chunks().get(n);if(chunk==null||chunk.stagePart()!=n||!SourcePartitionPlanner.rootId(plan.logicalKey(),plan.generation(),n).equals(chunk.rootId()))throw invalid();}
    }
    private static String owner(String key,long generation){return JdbcFrozenInputs.stableId("intent",key+":"+generation);}
    private static IllegalStateException invalid(){return new IllegalStateException("source stage birth proof unavailable");}
    @Override public String toString(){return "SourceStageBirthProof[redacted]";}
}
