package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.articles.ObservationPayloadPolicy;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure version identity from validated evidence. This value grants no native qualification. */
public final class CatalogVersionEvidenceMarker {
    public static final class Marker {
        private final String category,identity,json,hash;
        private Marker(String category,String identity,JsonNode semantic){
            this.category=category;this.identity=identity;
            var body=JsonMapper.shared().createObjectNode().put("schema_version","catalog-version-marker-v1").put("category",category);body.set("identity",CanonicalJson.parse(identity));body.set("version",semantic);
            json=new String(CanonicalJson.bytes(body),java.nio.charset.StandardCharsets.UTF_8);if(CanonicalJson.bytes(body).length>16384)throw invalid();hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,body);
        }
        public String category(){return category;}public String identityJson(){return identity;}public String json(){return json;}public String hash(){return hash;}
        @Override public String toString(){return "CatalogVersionEvidenceMarker[redacted]";}
    }
    private CatalogVersionEvidenceMarker(){ }
    /** Missing a known marker is unknown. Malformed or contradictory evidence is rejected. */
    public static Optional<Marker> read(JsonNode original,String sourceId){
        try{WireValues.id(sourceId,true);return extract(original,sourceId);}catch(RuntimeException rejected){throw invalid();}
    }
    /** Closed catalog/metric types have no source-specific parsing rule. This creates no source identity. */
    public static Optional<Marker> readCatalog(JsonNode original){return extract(original,null);}
    private static Optional<Marker> extract(JsonNode original,String sourceId){
        try{
            Objects.requireNonNull(original);
            var payload=CanonicalJson.parse(CanonicalJson.bytes(original));boolean metric=payload.has("subject");ObservationPayloadPolicy.validateStored(metric?"metric":"catalog",payload,sourceId);
            var subject=metric?payload.get("subject"):payload;String category=WireValues.text(subject.get("category"));String identity=new String(CanonicalJson.bytes(subject.get("identity")),java.nio.charset.StandardCharsets.UTF_8);
            var version=metric?benchmark(payload):"skill".equals(category)?skill(payload):release(payload);
            return version==null?Optional.empty():Optional.of(new Marker(category,identity,version));
        }catch(RuntimeException rejected){throw invalid();}
    }
    public static boolean changed(Marker previous,Marker current){
        if(previous==null||current==null||!previous.category.equals(current.category)||!previous.identity.equals(current.identity))throw invalid();return !previous.hash.equals(current.hash);
    }
    private static JsonNode release(JsonNode payload){
        var value=payload.get("version");if(value==null||value.isNull()||WireValues.text(value).isBlank())return null;String version=WireValues.text(value);boolean found=false;
        for(var evidence:payload.get("evidence"))if("release".equals(WireValues.text(evidence.get("evidence_type")))){
            var revision=evidence.get("revision");if(revision==null||revision.isNull()||!version.equals(WireValues.text(revision)))throw invalid();found=true;
        }
        return found?JsonMapper.shared().createObjectNode().put("kind","release").put("version",version):null;
    }
    private static JsonNode skill(JsonNode payload){
        JsonNode selected=null;
        for(var evidence:payload.get("evidence"))if("skill_document".equals(WireValues.text(evidence.get("evidence_type")))){
            String path=WireValues.text(evidence.get("path"));String revision=WireValues.text(evidence.get("revision"));var hash=evidence.get("file_hash");
            if(!(path.equals("SKILL.md")||path.endsWith("/SKILL.md"))||path.startsWith("/")||path.contains("\\")||Arrays.asList(path.split("/",-1)).stream().anyMatch(p->p.isEmpty()||p.equals(".")||p.equals(".."))||!revision.matches("[0-9a-f]{40}"))throw invalid();
            if(hash==null||hash.isNull())return null;if(selected!=null)throw invalid();selected=JsonMapper.shared().createObjectNode().put("kind","skill_document").put("path",path).put("file_hash",WireValues.sha256(hash));
        }
        // Repository commit movement alone does not change the selected SKILL.md bytes.
        return selected;
    }
    private static JsonNode benchmark(JsonNode payload){
        String version=WireValues.text(payload.get("benchmark_version"));var parameters=payload.get("evaluation_config").get("parameters");
        if(version.startsWith("aggregation_snapshot:")||"unknown".equals(parameters.path("original_benchmark_version").asText()))return null;
        boolean proved=false;for(var evidence:payload.get("evidence"))if("benchmark".equals(WireValues.text(evidence.get("evidence_type")))){
            var hash=evidence.get("file_hash");var revision=evidence.get("revision");var path=evidence.get("path");if(hash==null||hash.isNull()||revision==null||revision.isNull()||path==null||path.isNull())return null;WireValues.sha256(hash);if(WireValues.text(revision).isBlank()||WireValues.text(path).isBlank())throw invalid();proved=true;
        }
        if(!proved||payload.get("score").isNull())return null;
        var semantic=JsonMapper.shared().createObjectNode().put("kind","benchmark_result");
        // Exact subject/task/configuration score facts, not the whole score file's hash or commit.
        for(String field:List.of("benchmark","benchmark_version","task","score","unit","scale_min","scale_max","higher_is_better","evaluated_at","evaluation_scope","evaluation_config","provenance")){var value=payload.get(field);if(Set.of("score","scale_min","scale_max").contains(field)&&!value.isNull())semantic.put(field,new java.math.BigDecimal(WireValues.text(value)).stripTrailingZeros().toPlainString());else semantic.set(field,value.deepCopy());}
        return semantic;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("catalog version evidence marker unavailable");}
}
