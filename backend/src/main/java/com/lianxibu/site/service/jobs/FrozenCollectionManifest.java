package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.jobs.CollectionHandoffProofService.Proof;
import com.lianxibu.site.service.sources.CollectionHandoffService.Facts;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact collection handoffs in an immutable input; AI root dependencies remain separate. */
public final class FrozenCollectionManifest {
    public static final String FIELD="collection_manifest_v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> FACT_FIELDS=Set.of("runId","sourceId","runKind","businessDate",
            "generation","category","activationStage","configVersion","configHash","manifestHash",
            "deliveryState","fetchState","expected","accepted","excluded");
    private FrozenCollectionManifest() { }

    public static JsonNode create(List<Proof> handoffs) {
        try {
            if(handoffs==null||handoffs.size()>128)throw invalid();
            var sorted=new TreeMap<String,Proof>();
            for(var p:handoffs) {
                if(p==null||p.facts()==null||p.payloadHash()==null||!p.payloadHash().matches("[0-9a-f]{64}"))throw invalid();
                WireValues.id(p.intentId(),false);WireValues.id(p.dispositionId(),false);
                if(sorted.put(p.facts().runId(),p)!=null)throw invalid();
            }
            var root=JSON.createObjectNode();root.put("schema_version",FIELD);var items=root.putArray("handoffs");
            for(var p:sorted.values()) {
                var item=items.addObject();item.put("intent_id",p.intentId());item.put("disposition_id",p.dispositionId());
                item.put("payload_hash",p.payloadHash());item.set("facts",JSON.valueToTree(p.facts()));
            }
            if(CanonicalJson.bytes(root).length>262144)throw invalid();
            return root;
        }catch(RuntimeException bad){throw invalid();}
    }

    public static List<Proof> read(JsonNode node) {
        try {
            closed(node,Set.of("schema_version","handoffs"));
            if(!FIELD.equals(WireValues.text(node.get("schema_version")))||!node.get("handoffs").isArray()
                    ||node.get("handoffs").size()>128||CanonicalJson.bytes(node).length>262144)throw invalid();
            var proofs=new ArrayList<Proof>();
            for(var item:node.get("handoffs")) {
                closed(item,Set.of("intent_id","disposition_id","payload_hash","facts"));
                var f=item.get("facts");closed(f,FACT_FIELDS);
                var facts=new Facts(text(f,"runId"),text(f,"sourceId"),text(f,"runKind"),
                        WireValues.businessDate(f.get("businessDate")),integer(f,"generation"),text(f,"category"),
                        text(f,"activationStage"),integer(f,"configVersion"),text(f,"configHash"),text(f,"manifestHash"),
                        text(f,"deliveryState"),text(f,"fetchState"),integer(f,"expected"),integer(f,"accepted"),integer(f,"excluded"));
                proofs.add(new Proof(text(item,"intent_id"),text(item,"disposition_id"),text(item,"payload_hash"),facts));
            }
            if(!Arrays.equals(CanonicalJson.bytes(node),CanonicalJson.bytes(create(proofs))))throw invalid();
            return List.copyOf(proofs);
        }catch(RuntimeException bad){throw invalid();}
    }
    private static void closed(JsonNode value,Set<String> names){WireValues.object(value,names,names);}
    private static String text(JsonNode value,String name){return WireValues.text(value.get(name));}
    private static long integer(JsonNode value,String name){return WireValues.nonnegativeInteger(value.get(name));}
    private static IllegalStateException invalid(){return new IllegalStateException("invalid frozen collection manifest");}
}
