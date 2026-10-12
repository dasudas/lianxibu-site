package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Complete original final output blocks; staged blocks alone never establish completion. */
public final class FrozenCatalogSourceFinal {
    public static final int CHUNK_BYTES=128*1024,MAX_BYTES=CatalogSourceFinalResult.MAX_BYTES,MAX_CHUNKS=MAX_BYTES/CHUNK_BYTES;
    public static final int INDEX_BYTES=64*1024,ROW_BYTES=256*1024;
    private FrozenCatalogSourceFinal(){ }
    public record Reference(String ownerId,String resultHash,String indexHash){
        public Reference {WireValues.id(ownerId,false);if(ownerId.length()>64)throw invalid();hash(resultHash);hash(indexHash);}
        public String json(){return canonical(JsonMapper.shared().createObjectNode().put("schema_version","catalog-source-final-ref-v1").put("owner_job_id",ownerId).put("result_hash",resultHash).put("index_hash",indexHash));}
        public static Reference fromJson(String raw){if(raw==null||utf8(raw)>1024)throw invalid();var n=CanonicalJson.parse(raw);closed(n,"schema_version","owner_job_id","result_hash","index_hash");version(n,"catalog-source-final-ref-v1");return new Reference(text(n,"owner_job_id"),text(n,"result_hash"),text(n,"index_hash"));}
    }
    public record Encoded(Reference reference,List<String> payloads){
        public Encoded {Objects.requireNonNull(reference);payloads=List.copyOf(payloads);}
        @Override public String toString(){return "FrozenCatalogSourceFinal[redacted]";}
    }
    public static Encoded encode(CatalogSourceFinalResult original){return encodeWithinCapacity(original,MAX_BYTES);}
    static Encoded encodeWithinCapacity(CatalogSourceFinalResult original,int maximum){
        try {
            Objects.requireNonNull(original);if(maximum<1||maximum>MAX_BYTES)throw invalid();byte[] bytes=original.json().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>maximum)throw new GenerationFlowService.CapacityGuarded();var body=CanonicalJson.parse(bytes);
            if(!Arrays.equals(bytes,CanonicalJson.bytes(body))||!original.hash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body)))throw invalid();
            var plan=FrozenSourceStagePlan.Reference.fromJson(canonical(body.get("plan_ref")));
            var index=JsonMapper.shared().createObjectNode().put("schema_version","catalog-source-final-index-v1").put("owner_job_id",plan.ownerId()).put("result_hash",original.hash()).put("byte_count",bytes.length);
            var descriptors=index.putArray("parts");var payloads=new ArrayList<String>();payloads.add(null);
            for(int offset=0;offset<bytes.length;offset+=CHUNK_BYTES){
                var chunk=JsonMapper.shared().createObjectNode().put("schema_version","catalog-source-final-chunk-v1").put("bytes_base64",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+CHUNK_BYTES))));
                descriptors.addObject().put("part_no",payloads.size()).put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk));String payload=canonical(chunk);if(utf8(payload)>ROW_BYTES)throw invalid();payloads.add(payload);
            }
            String payload=canonical(index);if(utf8(payload)>INDEX_BYTES)throw new GenerationFlowService.CapacityGuarded();payloads.set(0,payload);
            return new Encoded(new Reference(plan.ownerId(),original.hash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index)),payloads);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Checks stored complete bytes against the actual original final proof, never a caller's edited JSON. */
    public static CatalogSourceFinalResult decode(Reference reference,List<String> payloads,CatalogSourceFinalResult original){
        try {
            Objects.requireNonNull(reference);Objects.requireNonNull(original);
            var owner=FrozenSourceStagePlan.Reference.fromJson(canonical(CanonicalJson.parse(original.json()).get("plan_ref"))).ownerId();
            if(!owner.equals(reference.ownerId())||!original.hash().equals(reference.resultHash())||payloads==null||payloads.size()<2||payloads.size()>MAX_CHUNKS+1||utf8(payloads.getFirst())>INDEX_BYTES)throw invalid();
            var index=CanonicalJson.parse(payloads.getFirst());closed(index,"schema_version","owner_job_id","result_hash","byte_count","parts");version(index,"catalog-source-final-index-v1");
            if(!reference.ownerId().equals(text(index,"owner_job_id"))||!reference.resultHash().equals(text(index,"result_hash"))||!reference.indexHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index)))throw invalid();
            long count=number(index,"byte_count");if(count<1||count>MAX_BYTES)throw invalid();var descriptors=index.get("parts");
            if(!descriptors.isArray()||descriptors.size()!=payloads.size()-1||descriptors.size()!=(count+CHUNK_BYTES-1)/CHUNK_BYTES)throw invalid();
            var output=new ByteArrayOutputStream();
            for(int n=1;n<payloads.size();n++){
                var descriptor=descriptors.get(n-1);closed(descriptor,"part_no","payload_hash");if(number(descriptor,"part_no")!=n||utf8(payloads.get(n))>ROW_BYTES)throw invalid();
                var chunk=CanonicalJson.parse(payloads.get(n));closed(chunk,"schema_version","bytes_base64");version(chunk,"catalog-source-final-chunk-v1");
                if(!text(descriptor,"payload_hash").equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk)))throw invalid();
                String encoded=text(chunk,"bytes_base64");if(encoded.length()>(CHUNK_BYTES+2)/3*4)throw invalid();byte[] bytes=Base64.getDecoder().decode(encoded);
                if(bytes.length<1||bytes.length>CHUNK_BYTES||n<payloads.size()-1&&bytes.length!=CHUNK_BYTES||!encoded.equals(Base64.getEncoder().encodeToString(bytes))||output.size()+bytes.length>count)throw invalid();output.writeBytes(bytes);
            }
            if(output.size()!=count)throw invalid();byte[] bytes=output.toByteArray();var body=CanonicalJson.parse(bytes);
            if(!Arrays.equals(bytes,CanonicalJson.bytes(body))||!reference.resultHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body))||!Arrays.equals(bytes,original.json().getBytes(StandardCharsets.UTF_8)))throw invalid();return original;
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static void closed(JsonNode n,String... names){var fields=Set.of(names);WireValues.object(n,fields,fields);}
    private static void version(JsonNode n,String expected){if(!expected.equals(text(n,"schema_version")))throw invalid();}
    private static String text(JsonNode n,String field){return WireValues.text(n.get(field));}
    private static long number(JsonNode n,String field){return WireValues.nonnegativeInteger(n.get(field));}
    private static String canonical(JsonNode n){return new String(CanonicalJson.bytes(n),StandardCharsets.UTF_8);}
    private static int utf8(String raw){if(raw==null)throw invalid();return raw.getBytes(StandardCharsets.UTF_8).length;}
    private static void hash(String raw){if(raw==null||!raw.matches("[0-9a-f]{64}"))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original final catalog source archive unavailable");}
}
