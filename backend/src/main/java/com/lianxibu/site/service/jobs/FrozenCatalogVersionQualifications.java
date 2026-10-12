package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed original result constraints only. Parsing these values grants no native qualification. */
public final class FrozenCatalogVersionQualifications {
    public record Qualification(String itemId,String entityId,boolean versionChanged,String previousProofHash,String newProofHash){
        public Qualification {WireValues.id(itemId,false);WireValues.id(entityId,false);if(previousProofHash==null||newProofHash==null||!previousProofHash.matches("[0-9a-f]{64}")||!newProofHash.matches("[0-9a-f]{64}")||previousProofHash.equals(newProofHash))throw invalid();}
        @Override public String toString(){return "FrozenCatalogVersionQualification[redacted]";}
    }
    private final Map<String,Qualification> entries;
    private final String json;
    private FrozenCatalogVersionQualifications(Map<String,Qualification> entries){
        this.entries=Map.copyOf(entries);var array=JsonMapper.shared().createArrayNode();
        entries.values().stream().sorted((a,b)->utf8(a.itemId(),b.itemId())).forEach(q->{var item=array.addObject().put("item_id",q.itemId()).put("entity_id",q.entityId()).put("version_changed",q.versionChanged());item.set("previous_evidence_ref",reference(q.previousProofHash()));item.set("new_evidence_ref",reference(q.newProofHash()));});
        json=new String(CanonicalJson.bytes(array),StandardCharsets.UTF_8);
    }
    public Map<String,Qualification> entries(){return entries;}public JsonNode toJson(){return CanonicalJson.parse(json);}
    public static FrozenCatalogVersionQualifications read(JsonNode original,Set<String> items,Map<String,Set<String>> candidates,Set<String> eligible){
        if(original==null||!original.isArray()||original.size()>1000||items==null||candidates==null||eligible==null)throw invalid();
        var entries=new HashMap<String,Qualification>();var changed=new HashSet<String>();
        for(var value:original){
            var fields=Set.of("item_id","entity_id","version_changed","previous_evidence_ref","new_evidence_ref");WireValues.object(value,fields,fields);
            String item=WireValues.id(value.get("item_id"),false),entity=WireValues.id(value.get("entity_id"),false);if(!value.get("version_changed").isBoolean()||!items.contains(item)||!candidates.getOrDefault(item,Set.of()).contains(entity))throw invalid();
            boolean isChanged=value.get("version_changed").booleanValue();var q=new Qualification(item,entity,isChanged,hash(value.get("previous_evidence_ref")),hash(value.get("new_evidence_ref")));
            if(entries.putIfAbsent(item,q)!=null)throw invalid();if(isChanged)changed.add(item);
        }
        if(!changed.equals(eligible))throw invalid();return new FrozenCatalogVersionQualifications(entries);
    }
    private static String hash(JsonNode ref){var fields=Set.of("kind","proof_hash");WireValues.object(ref,fields,fields);if(!"catalog_version_evidence".equals(WireValues.text(ref.get("kind"))))throw invalid();return WireValues.sha256(ref.get("proof_hash"));}
    private static JsonNode reference(String hash){return JsonMapper.shared().createObjectNode().put("kind","catalog_version_evidence").put("proof_hash",hash);}
    private static int utf8(String a,String b){return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid frozen catalog version qualifications");}
}
