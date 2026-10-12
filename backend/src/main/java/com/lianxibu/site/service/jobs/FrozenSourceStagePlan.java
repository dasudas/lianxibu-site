package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Original complete decisions and provider messages, independently of queued roots. */
public final class FrozenSourceStagePlan {
    public static final int CHUNK_BYTES=128*1024,MAX_CHUNKS=512,MAX_BYTES=CHUNK_BYTES*MAX_CHUNKS;
    public static final int INDEX_BYTES=64*1024,ROW_BYTES=256*1024;
    private static final JsonMapper JSON=JsonMapper.shared();
    private FrozenSourceStagePlan(){ }
    public record Reference(String ownerId,String planHash,String indexHash) {
        public Reference {WireValues.id(ownerId,false);if(ownerId.length()>64)throw invalid();hash(planHash);hash(indexHash);}
        public String json(){var n=JSON.createObjectNode();n.put("schema_version","source-stage-plan-ref-v1");n.put("owner_job_id",ownerId);n.put("plan_hash",planHash);n.put("index_hash",indexHash);return canonical(n);}
        public static Reference fromJson(String raw){
            if(raw==null||utf8(raw)>1024)throw invalid();var n=CanonicalJson.parse(raw);closed(n,"schema_version","owner_job_id","plan_hash","index_hash");version(n,"source-stage-plan-ref-v1");
            return new Reference(text(n,"owner_job_id"),text(n,"plan_hash"),text(n,"index_hash"));
        }
    }
    public record Encoded(Reference reference,List<String> payloads) {
        public Encoded {Objects.requireNonNull(reference);payloads=List.copyOf(payloads);}
        @Override public String toString(){return "FrozenSourceStagePlan[redacted]";}
    }
    public static Encoded encode(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe) {
        try {
            new SourcePartitionPlanner().validate(plan,universe);
            if(plan.scope()==null)throw invalid();
            var body=JSON.createObjectNode();body.put("schema_version","source-stage-plan-v1");body.put("owner_job_id",plan.reference().ownerId());
            body.put("stage",plan.stage().name().toLowerCase(Locale.ROOT));body.put("logical_key",plan.logicalKey());body.put("generation",plan.generation());
            body.set("universe_ref",CanonicalJson.parse(plan.reference().json()));var decisions=body.putArray("decisions");
            var scope=body.putObject("scope");scope.put("schema_version",scopeVersion(plan.scope()));scope.put("business_date",plan.scope().businessDate().toString());
            scope.put("cutoff",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(plan.scope().cutoff()));scope.set(FrozenGenerationSettings.FIELD,plan.scope().settings().toJson());
            if(plan.scope().stageContextJson()!=null)scope.set("stage_context",CanonicalJson.parse(plan.scope().stageContextJson()));
            for(var decision:plan.decisions()) {var n=decisions.addObject();n.put("item_id",decision.itemId());n.put("reason",decision.reason().name());}
            var chunks=body.putArray("chunks");long estimated=CanonicalJson.bytes(body).length;
            for(var chunk:plan.chunks()) {
                var n=JSON.createObjectNode();n.put("stage_part",chunk.stagePart());n.put("root_id",chunk.rootId());var ids=n.putArray("item_ids");chunk.itemIds().forEach(ids::add);
                n.put("snapshot_json",chunk.snapshotJson());n.put("input_hash",chunk.inputHash());n.put("request_json",chunk.requestJson());
                estimated+=CanonicalJson.bytes(n).length+1;if(estimated>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();chunks.add(n);
            }
            byte[] bytes=CanonicalJson.bytes(body);if(bytes.length>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
            String planHash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,body);
            var index=JSON.createObjectNode();index.put("schema_version","source-stage-plan-index-v1");index.put("owner_job_id",plan.reference().ownerId());
            index.put("plan_hash",planHash);index.put("byte_count",bytes.length);index.put("member_count",plan.decisions().size());index.put("stage_part_count",plan.chunks().size());
            var descriptors=index.putArray("parts");var payloads=new ArrayList<String>();payloads.add(null);
            for(int offset=0;offset<bytes.length;offset+=CHUNK_BYTES) {
                var chunk=JSON.createObjectNode();chunk.put("schema_version","source-stage-plan-chunk-v1");
                chunk.put("bytes_base64",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+CHUNK_BYTES))));
                var descriptor=descriptors.addObject();descriptor.put("part_no",payloads.size());descriptor.put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk));payloads.add(canonical(chunk));
            }
            String summary=canonical(index);if(utf8(summary)>INDEX_BYTES)throw new GenerationFlowService.CapacityGuarded();payloads.set(0,summary);
            return new Encoded(new Reference(plan.reference().ownerId(),planHash,CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index)),payloads);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw invalid();}
    }
    public static SourcePartitionPlanner.Plan decode(Reference reference,List<String> payloads,MemberUniverse.Universe universe) {
        try {
            Objects.requireNonNull(reference);Objects.requireNonNull(universe);
            if(payloads==null||payloads.size()<2||payloads.size()>MAX_CHUNKS+1||utf8(payloads.getFirst())>INDEX_BYTES)throw invalid();
            var index=CanonicalJson.parse(payloads.getFirst());closed(index,"schema_version","owner_job_id","plan_hash","byte_count","member_count","stage_part_count","parts");version(index,"source-stage-plan-index-v1");
            if(!reference.ownerId().equals(text(index,"owner_job_id"))||!reference.planHash().equals(text(index,"plan_hash"))
                    ||!reference.indexHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index)))throw invalid();
            long byteCount=number(index,"byte_count"),memberCount=number(index,"member_count"),stageParts=number(index,"stage_part_count");
            if(byteCount<1||byteCount>MAX_BYTES||memberCount!=universe.members().size()||stageParts>MemberUniverse.MAX_MEMBERS)throw invalid();
            var descriptors=index.get("parts");if(!descriptors.isArray()||descriptors.size()!=payloads.size()-1||descriptors.size()!=(byteCount+CHUNK_BYTES-1)/CHUNK_BYTES)throw invalid();
            var output=new ByteArrayOutputStream();
            for(int n=1;n<payloads.size();n++) {
                var descriptor=descriptors.get(n-1);closed(descriptor,"part_no","payload_hash");if(number(descriptor,"part_no")!=n||utf8(payloads.get(n))>ROW_BYTES)throw invalid();
                var chunk=CanonicalJson.parse(payloads.get(n));closed(chunk,"schema_version","bytes_base64");version(chunk,"source-stage-plan-chunk-v1");
                if(!text(descriptor,"payload_hash").equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk)))throw invalid();
                String encoded=text(chunk,"bytes_base64");if(encoded.length()>(CHUNK_BYTES+2)/3*4)throw invalid();byte[] bytes=Base64.getDecoder().decode(encoded);
                if(bytes.length<1||bytes.length>CHUNK_BYTES||n<payloads.size()-1&&bytes.length!=CHUNK_BYTES
                        ||!encoded.equals(Base64.getEncoder().encodeToString(bytes))||output.size()+bytes.length>byteCount)throw invalid();output.writeBytes(bytes);
            }
            if(output.size()!=byteCount)throw invalid();byte[] bytes=output.toByteArray();var body=CanonicalJson.parse(bytes);
            closed(body,"schema_version","owner_job_id","stage","logical_key","generation","universe_ref","scope","decisions","chunks");version(body,"source-stage-plan-v1");
            if(!Arrays.equals(bytes,CanonicalJson.bytes(body))||!reference.planHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,body))||!reference.ownerId().equals(text(body,"owner_job_id")))throw invalid();
            String rawStage=text(body,"stage");Stage stage=Stage.valueOf(rawStage.toUpperCase(Locale.ROOT));if(!rawStage.equals(stage.name().toLowerCase(Locale.ROOT)))throw invalid();
            var source=FrozenSourceUniverse.Reference.fromJson(canonical(body.get("universe_ref")));if(!reference.ownerId().equals(source.ownerId()))throw invalid();
            var rawScope=body.get("scope");String scopeVersion=text(rawScope,"schema_version");boolean day="source-stage-scope-v4".equals(scopeVersion),partial="source-stage-scope-v3".equals(scopeVersion),extended=day||partial||"source-stage-scope-v2".equals(scopeVersion);
            if(extended)closed(rawScope,"schema_version","business_date","cutoff",FrozenGenerationSettings.FIELD,"stage_context");else {closed(rawScope,"schema_version","business_date","cutoff",FrozenGenerationSettings.FIELD);version(rawScope,"source-stage-scope-v1");}
            if(extended&&!(day?"source-value-stage-context-v3":partial?"source-value-stage-context-v2":"source-value-stage-context-v1").equals(WireValues.text(rawScope.get("stage_context").get("schema_version"))))throw invalid();
            var scope=new SourcePartitionPlanner.Scope(java.time.LocalDate.parse(text(rawScope,"business_date")),WireValues.utcMicroseconds(rawScope.get("cutoff")),FrozenGenerationSettings.read(rawScope.get(FrozenGenerationSettings.FIELD)),extended?canonical(rawScope.get("stage_context")):null);
            var rawDecisions=body.get("decisions");var rawChunks=body.get("chunks");if(!rawDecisions.isArray()||rawDecisions.size()!=memberCount||!rawChunks.isArray()||rawChunks.size()!=stageParts)throw invalid();
            var decisions=new ArrayList<SourcePartitionPlanner.Decision>();
            for(var d:rawDecisions){closed(d,"item_id","reason");decisions.add(new SourcePartitionPlanner.Decision(text(d,"item_id"),SourcePartitionPlanner.Reason.valueOf(text(d,"reason"))));}
            var chunks=new ArrayList<SourcePartitionPlanner.Chunk>();
            for(var c:rawChunks) {
                closed(c,"stage_part","root_id","item_ids","snapshot_json","input_hash","request_json");var rawIds=c.get("item_ids");if(!rawIds.isArray()||rawIds.isEmpty()||rawIds.size()>100)throw invalid();
                var ids=new ArrayList<String>();for(var id:rawIds)ids.add(WireValues.id(id,false));
                String snapshot=text(c,"snapshot_json"),request=text(c,"request_json");if(utf8(snapshot)>1048576||utf8(request)>1048576)throw invalid();
                chunks.add(new SourcePartitionPlanner.Chunk(number(c,"stage_part"),text(c,"root_id"),ids,snapshot,text(c,"input_hash"),request));
            }
            var plan=new SourcePartitionPlanner.Plan(stage,text(body,"logical_key"),number(body,"generation"),source,decisions,chunks,scope);
            new SourcePartitionPlanner().validate(plan,universe);return plan;
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static String scopeVersion(SourcePartitionPlanner.Scope scope){
        if(scope.stageContextJson()==null)return "source-stage-scope-v1";
        return switch(WireValues.text(CanonicalJson.parse(scope.stageContextJson()).get("schema_version"))){case "source-value-stage-context-v1"->"source-stage-scope-v2";case "source-value-stage-context-v2"->"source-stage-scope-v3";case "source-value-stage-context-v3"->"source-stage-scope-v4";default->throw invalid();};
    }
    private static void closed(JsonNode n,String...names){var fields=Set.of(names);WireValues.object(n,fields,fields);}
    private static void version(JsonNode n,String expected){if(!expected.equals(text(n,"schema_version")))throw invalid();}
    private static String text(JsonNode n,String field){return WireValues.text(n.get(field));}
    private static long number(JsonNode n,String field){return WireValues.nonnegativeInteger(n.get(field));}
    private static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static int utf8(String raw){if(raw==null)throw invalid();return raw.getBytes(StandardCharsets.UTF_8).length;}
    private static String canonical(JsonNode n){return new String(CanonicalJson.bytes(n),StandardCharsets.UTF_8);}
    private static IllegalStateException invalid(){return new IllegalStateException("frozen source stage plan proof unavailable");}
}
