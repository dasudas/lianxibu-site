package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Entire original membership proof, separate from the bounded model message. */
public final class FrozenSourceUniverse {
    public static final int CHUNK_BYTES=128*1024,MAX_CHUNKS=512,MAX_BYTES=CHUNK_BYTES*MAX_CHUNKS;
    public static final int INDEX_BYTES=64*1024,ROW_BYTES=256*1024;
    private static final JsonMapper JSON=JsonMapper.shared();
    private FrozenSourceUniverse() { }
    public record Reference(String ownerId,String runId,String universeHash,String indexHash) {
        public Reference {id(ownerId,64);id(runId,64);hash(universeHash);hash(indexHash);}
        public String json() {
            var node=JSON.createObjectNode();node.put("schema_version","source-universe-ref-v1");
            node.put("owner_job_id",ownerId);node.put("run_id",runId);node.put("universe_hash",universeHash);node.put("index_hash",indexHash);
            return canonical(node);
        }
        public static Reference fromJson(String json) {
            if(json==null||json.length()>1024)throw invalid();
            var node=CanonicalJson.parse(json);closed(node,"schema_version","owner_job_id","run_id","universe_hash","index_hash");
            version(node,"source-universe-ref-v1");return new Reference(text(node,"owner_job_id"),text(node,"run_id"),text(node,"universe_hash"),text(node,"index_hash"));
        }
    }
    public record Encoded(Reference reference,List<String> payloads) {
        public Encoded {Objects.requireNonNull(reference);payloads=List.copyOf(payloads);}
        @Override public String toString(){return "FrozenSourceUniverse[redacted]";}
    }
    public static Encoded encode(String owner,MemberUniverse.Universe universe) {
        try {
            id(owner,64);Objects.requireNonNull(universe);
            var body=JSON.createObjectNode();body.put("schema_version","source-universe-v1");
            body.put("run_id",universe.runId());body.put("source_id",universe.sourceId());body.put("manifest_hash",universe.manifestHash());
            body.put("accepted_count",universe.accepted());body.put("excluded_count",universe.excluded());
            var originalParts=body.putArray("parts");
            for(var part:universe.parts()) {
                var n=originalParts.addObject();n.put("part_no",part.partNo());n.put("part_hash",part.partHash());n.put("member_count",part.memberCount());
            }
            var members=body.putArray("members");
            for(var member:universe.members()) {
                var n=members.addObject();n.put("item_id",member.itemId());n.put("member_id",member.memberId());
                nullable(n,"source_item_id",member.sourceItemId());n.put("observation_id",member.observationId());n.put("payload_hash",member.payloadHash());
                nullable(n,"replaces_observation_id",member.replacesObservationId());n.put("part_no",member.partNo());n.put("terminal_state",member.terminalState());
            }
            byte[] bytes=CanonicalJson.bytes(body);if(bytes.length>MAX_BYTES)throw invalid();
            String universeHash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,body);
            var index=JSON.createObjectNode();index.put("schema_version","source-universe-index-v1");index.put("owner_job_id",owner);
            index.put("run_id",universe.runId());index.put("source_id",universe.sourceId());index.put("manifest_hash",universe.manifestHash());
            index.put("universe_hash",universeHash);index.put("byte_count",bytes.length);index.put("member_count",universe.members().size());
            index.put("accepted_count",universe.accepted());index.put("excluded_count",universe.excluded());var descriptors=index.putArray("parts");
            var payloads=new ArrayList<String>();payloads.add(null);
            for(int offset=0;offset<bytes.length;offset+=CHUNK_BYTES) {
                var chunk=JSON.createObjectNode();chunk.put("schema_version","source-universe-chunk-v1");
                chunk.put("bytes_base64",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+CHUNK_BYTES))));
                var descriptor=descriptors.addObject();descriptor.put("part_no",payloads.size());
                descriptor.put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk));payloads.add(canonical(chunk));
            }
            String summary=canonical(index);if(utf8(summary)>INDEX_BYTES)throw invalid();payloads.set(0,summary);
            return new Encoded(new Reference(owner,universe.runId(),universeHash,CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index)),payloads);
        }catch(RuntimeException rejected){throw invalid();}
    }
    public static MemberUniverse.Universe decode(Reference reference,List<String> payloads) {
        try {
            Objects.requireNonNull(reference);
            if(payloads==null||payloads.size()<2||payloads.size()>MAX_CHUNKS+1||utf8(payloads.getFirst())>INDEX_BYTES)throw invalid();
            var index=CanonicalJson.parse(payloads.getFirst());
            closed(index,"schema_version","owner_job_id","run_id","source_id","manifest_hash","universe_hash","byte_count","member_count","accepted_count","excluded_count","parts");
            version(index,"source-universe-index-v1");
            if(!reference.indexHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index))
                    ||!reference.ownerId().equals(text(index,"owner_job_id"))||!reference.runId().equals(text(index,"run_id"))
                    ||!reference.universeHash().equals(text(index,"universe_hash")))throw invalid();
            long byteCount=number(index,"byte_count"),count=number(index,"member_count"),accepted=number(index,"accepted_count"),excluded=number(index,"excluded_count");
            if(byteCount<1||byteCount>MAX_BYTES||count>MemberUniverse.MAX_MEMBERS||accepted>count||excluded!=count-accepted)throw invalid();
            var parts=index.get("parts");if(!parts.isArray()||parts.size()!=payloads.size()-1||parts.size()!=(byteCount+CHUNK_BYTES-1)/CHUNK_BYTES)throw invalid();
            var output=new ByteArrayOutputStream();
            for(int n=1;n<payloads.size();n++) {
                var descriptor=parts.get(n-1);closed(descriptor,"part_no","payload_hash");
                if(number(descriptor,"part_no")!=n||utf8(payloads.get(n))>ROW_BYTES)throw invalid();
                var chunk=CanonicalJson.parse(payloads.get(n));closed(chunk,"schema_version","bytes_base64");version(chunk,"source-universe-chunk-v1");
                if(!text(descriptor,"payload_hash").equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk)))throw invalid();
                String encoded=text(chunk,"bytes_base64");byte[] bytes=Base64.getDecoder().decode(encoded);
                if(bytes.length<1||bytes.length>CHUNK_BYTES||n<payloads.size()-1&&bytes.length!=CHUNK_BYTES
                        ||!encoded.equals(Base64.getEncoder().encodeToString(bytes))||output.size()+bytes.length>byteCount)throw invalid();
                output.writeBytes(bytes);
            }
            if(output.size()!=byteCount)throw invalid();byte[] bytes=output.toByteArray();var body=CanonicalJson.parse(bytes);
            closed(body,"schema_version","run_id","source_id","manifest_hash","accepted_count","excluded_count","parts","members");version(body,"source-universe-v1");
            if(!Arrays.equals(bytes,CanonicalJson.bytes(body))||!reference.universeHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,body))
                    ||!reference.runId().equals(text(body,"run_id"))||!text(index,"source_id").equals(text(body,"source_id"))
                    ||!text(index,"manifest_hash").equals(text(body,"manifest_hash"))||number(body,"accepted_count")!=accepted||number(body,"excluded_count")!=excluded)throw invalid();
            var rawParts=body.get("parts");var rawMembers=body.get("members");
            if(!rawParts.isArray()||rawParts.size()>MemberUniverse.MAX_PARTS||!rawMembers.isArray()||rawMembers.size()!=count)throw invalid();
            var originalParts=new ArrayList<MemberUniverse.Part>();var members=new ArrayList<MemberUniverse.Member>();
            for(var part:rawParts) {
                closed(part,"part_no","part_hash","member_count");long size=number(part,"member_count");if(size>100)throw invalid();
                originalParts.add(new MemberUniverse.Part(number(part,"part_no"),text(part,"part_hash"),(int)size));
            }
            for(var member:rawMembers) {
                closed(member,"item_id","member_id","source_item_id","observation_id","payload_hash","replaces_observation_id","part_no","terminal_state");
                members.add(new MemberUniverse.Member(text(member,"item_id"),text(member,"member_id"),nullable(member,"source_item_id"),
                        text(member,"observation_id"),text(member,"payload_hash"),nullable(member,"replaces_observation_id"),number(member,"part_no"),text(member,"terminal_state")));
            }
            return new MemberUniverse.Universe(text(body,"run_id"),text(body,"source_id"),text(body,"manifest_hash"),originalParts,members,accepted,excluded);
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static void closed(JsonNode node,String... names){var fields=Set.of(names);WireValues.object(node,fields,fields);}
    private static void version(JsonNode node,String expected){if(!expected.equals(text(node,"schema_version")))throw invalid();}
    private static String text(JsonNode node,String name){return WireValues.text(node.get(name));}
    private static long number(JsonNode node,String name){return WireValues.nonnegativeInteger(node.get(name));}
    private static String nullable(JsonNode node,String name){return node.get(name).isNull()?null:WireValues.text(node.get(name));}
    private static void nullable(tools.jackson.databind.node.ObjectNode node,String name,String value){if(value==null)node.putNull(name);else node.put(name,value);}
    private static void id(String value,int limit){if(value==null||!value.matches("[A-Za-z0-9._:-]{1,"+limit+"}"))throw invalid();}
    private static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static int utf8(String value){if(value==null)throw invalid();return value.getBytes(StandardCharsets.UTF_8).length;}
    private static String canonical(JsonNode node){return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);}
    private static IllegalStateException invalid(){return new IllegalStateException("frozen source universe proof unavailable");}
}
