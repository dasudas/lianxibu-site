package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.CollectionHandoffService.Facts;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Original metadata supplies a route. Preparation and completed handoff require their own proofs. */
public final class CatalogSourceIntentOriginService {
    public record Route(String intentId,String type,String runId,String category){
        @Override public String toString(){return "CatalogSourceIntentOriginRoute[redacted]";}
    }
    /** Issued only after the actual native intent and permanent original origin have been verified. */
    public static final class OriginalCollection {
        private final Route route;private final CollectionHandoffProofService.Proof collection;private final java.time.Instant actedAt;
        private OriginalCollection(Route route,CollectionHandoffProofService.Proof collection,java.time.Instant actedAt){this.route=route;this.collection=collection;this.actedAt=actedAt;}
        public Route route(){return route;}public CollectionHandoffProofService.Proof collection(){return collection;}public java.time.Instant actedAt(){return actedAt;}
        @Override public String toString(){return "OriginalCatalogCollection[redacted]";}
    }
    private final JdbcCatalogSourceIntentScan storage;private final String category,type;
    public CatalogSourceIntentOriginService(JdbcCatalogSourceIntentScan storage,String category){
        this.storage=Objects.requireNonNull(storage);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw unavailable();};type=category+"_dedup";
    }
    public Route read(JdbcSourceIntentScan.Candidate candidate){return original(candidate).route();}
    /** Complete immutable collection facts, including zero-plan coverage; current sources are never consulted. */
    public OriginalCollection readCollection(String intentId){
        try {JdbcSourceDedupResults.requireReadOnly();var candidate=storage.find(intentId);return candidate==null?null:original(candidate);}
        catch(RuntimeException rejected){throw unavailable();}
    }
    private OriginalCollection original(JdbcSourceIntentScan.Candidate candidate){
        try {
            JdbcSourceDedupResults.requireReadOnly();validate(candidate);
            var originals=storage.origins(candidate.id(),candidate.type());if(originals.size()!=1)throw unavailable();
            var raw=originals.getFirst();bounded(raw);var body=CanonicalJson.parse(raw.payload());
            {
                closed(body,"schema_version","intent_id","facts");
                if(!"collection-handoff-v1".equals(text(body,"schema_version"))||!candidate.id().equals(text(body,"intent_id")))throw unavailable();
                var facts=facts(body.get("facts"));
                if(!category.equals(facts.category())||!"production".equals(facts.activationStage())||"trial".equals(facts.runKind())
                        ||!candidate.key().equals(CollectionHandoffProofService.logicalKey(facts))||candidate.generation()!=facts.generation()||!candidate.day().equals(facts.businessDate()))throw unavailable();
                String action="collection-handoff:"+facts.runId();
                verified(raw,candidate,"collection_handoff","collection-worker","Terminal collection handed to analysis",JdbcFrozenInputs.stableId("collection-handoff",action),action);
                var expected=JsonMapper.shared().createObjectNode().put("schema_version","collection-handoff-v1").put("intent_id",candidate.id());
                expected.set("facts",JsonMapper.shared().valueToTree(facts));
                if(!canonical(body).equals(canonical(expected)))throw unavailable();
                return new OriginalCollection(new Route(candidate.id(),candidate.type(),facts.runId(),facts.category()),new CollectionHandoffProofService.Proof(candidate.id(),raw.id(),HexFormat.of().formatHex(raw.payloadHash()),facts),raw.actedAt());
            }
        }catch(RuntimeException rejected){throw unavailable();}
    }
    private void validate(JdbcSourceIntentScan.Candidate candidate){
        Objects.requireNonNull(candidate);String natural=candidate.key();
        if(natural==null||natural.isBlank()||bytes(natural)>4096||candidate.generation()<1||candidate.generation()>CanonicalJson.MAX_SAFE_INTEGER
                ||candidate.day()==null||candidate.revision()<0||candidate.revision()>CanonicalJson.MAX_SAFE_INTEGER
                ||!type.equals(candidate.type())||!Set.of("waiting_input","prepared","queued","running","succeeded","no_input").contains(candidate.state())
                ||!Arrays.equals(candidate.keyHash(),JdbcGenerationLineage.hash(natural))||!JdbcFrozenInputs.stableId("intent",natural+":"+candidate.generation()).equals(candidate.id()))throw unavailable();
    }
    private static void bounded(JdbcSourceIntentScan.Origin raw){
        if(raw.target()==null||raw.payload()==null||raw.targetBytes()==null||raw.payloadBytes()==null||raw.targetBytes()<1||raw.targetBytes()>2048||raw.payloadBytes()<1||raw.payloadBytes()>8192
                ||raw.targetBytes()!=bytes(raw.target())||raw.payloadBytes()!=bytes(raw.payload())||raw.actedAt()==null||raw.actedAt().getNano()%1000!=0)throw unavailable();
    }
    private static void verified(JdbcSourceIntentScan.Origin raw,JdbcSourceIntentScan.Candidate candidate,String type,String actor,String reason,String id,String action){
        var target=CanonicalJson.parse(raw.target());closed(target,"kind","id");
        if(!id.equals(raw.id())||!"disposition".equals(raw.kind())||!type.equals(raw.type())||!actor.equals(raw.actor())||!reason.equals(raw.reason())
                ||!"generation_intent".equals(text(target,"kind"))||!candidate.id().equals(text(target,"id"))
                ||!Arrays.equals(raw.targetHash(),JdbcGenerationLineage.hash(canonical(target)))
                ||!Arrays.equals(raw.payloadHash(),JdbcGenerationLineage.hash(canonical(CanonicalJson.parse(raw.payload()))))
                ||!Arrays.equals(raw.actionHash(),JdbcGenerationLineage.hash(action)))throw unavailable();
    }
    private static Facts facts(JsonNode node){
        closed(node,"runId","sourceId","runKind","businessDate","generation","category","activationStage","configVersion","configHash","manifestHash","deliveryState","fetchState","expected","accepted","excluded");
        return new Facts(WireValues.id(node.get("runId"),false),WireValues.id(node.get("sourceId"),true),text(node,"runKind"),WireValues.businessDate(node.get("businessDate")),integer(node,"generation"),text(node,"category"),text(node,"activationStage"),integer(node,"configVersion"),WireValues.sha256(node.get("configHash")),WireValues.sha256(node.get("manifestHash")),text(node,"deliveryState"),text(node,"fetchState"),integer(node,"expected"),integer(node,"accepted"),integer(node,"excluded"));
    }
    private static String text(JsonNode n,String field){return WireValues.text(n.get(field));}
    private static long integer(JsonNode n,String field){return WireValues.nonnegativeInteger(n.get(field));}
    private static String canonical(JsonNode n){return JdbcFrozenInputs.canonical(n);}
    private static long bytes(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    private static void closed(JsonNode n,String...fields){var expected=Set.of(fields);WireValues.object(n,expected,expected);}
    private static IllegalStateException unavailable(){return new IllegalStateException("original catalog source intent route unavailable");}
}
