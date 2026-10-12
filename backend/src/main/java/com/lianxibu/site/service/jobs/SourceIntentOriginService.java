package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.CollectionHandoffService.Facts;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Original metadata supplies a route. Preparation and completed handoff require their own proofs. */
public final class SourceIntentOriginService {
    public record Route(String intentId,String type,String runId,FrozenSourceStagePlan.Reference p1Ref){
        @Override public String toString(){return "SourceIntentOriginRoute[redacted]";}
    }
    private final JdbcSourceIntentScan storage;
    private final SourceValueIntentReceiptService values;
    public SourceIntentOriginService(JdbcSourceIntentScan storage,SourceValueIntentReceiptService values){
        this.storage=Objects.requireNonNull(storage);this.values=Objects.requireNonNull(values);
    }
    public Route read(JdbcSourceIntentScan.Candidate candidate){
        try {
            JdbcSourceDedupResults.requireReadOnly();validate(candidate);
            var originals=storage.origins(candidate.id(),candidate.type());if(originals.size()!=1)throw unavailable();
            var raw=originals.getFirst();bounded(raw);var body=CanonicalJson.parse(raw.payload());
            if("news_dedup".equals(candidate.type())){
                closed(body,"schema_version","intent_id","facts");
                if(!"collection-handoff-v1".equals(text(body,"schema_version"))||!candidate.id().equals(text(body,"intent_id")))throw unavailable();
                var facts=facts(body.get("facts"));
                if(!"news_tech".equals(facts.category())||!"production".equals(facts.activationStage())||"trial".equals(facts.runKind())
                        ||!candidate.key().equals(CollectionHandoffProofService.logicalKey(facts))||candidate.generation()!=facts.generation()||!candidate.day().equals(facts.businessDate()))throw unavailable();
                String action="collection-handoff:"+facts.runId();
                verified(raw,candidate,"collection_handoff","collection-worker","Terminal collection handed to analysis",JdbcFrozenInputs.stableId("collection-handoff",action),action);
                var expected=JsonMapper.shared().createObjectNode().put("schema_version","collection-handoff-v1").put("intent_id",candidate.id());
                expected.set("facts",JsonMapper.shared().valueToTree(facts));
                if(!canonical(body).equals(canonical(expected)))throw unavailable();
                return new Route(candidate.id(),candidate.type(),facts.runId(),null);
            }
            var value=SourceValueIntentReceiptService.Handoff.parse(canonical(body));
            if(!candidate.id().equals(value.p2Owner())||!candidate.key().equals(value.p2Key())||candidate.generation()!=value.p2Generation()||!candidate.day().equals(value.day()))throw unavailable();
            String owner=value.p1Ref().ownerId();
            verified(raw,candidate,JdbcSourceValueIntents.TYPE,JdbcSourceValueIntents.ACTOR,JdbcSourceValueIntents.REASON,SourceValueIntentReceiptService.receiptId(owner),JdbcSourceValueIntents.action(owner));
            var original=values.read(owner);
            if(original==null||!original.json().equals(canonical(body)))throw unavailable();
            return new Route(candidate.id(),candidate.type(),original.runId(),original.p1Ref());
        }catch(RuntimeException rejected){throw unavailable();}
    }
    private static void validate(JdbcSourceIntentScan.Candidate candidate){
        Objects.requireNonNull(candidate);String natural=candidate.key();
        if(natural==null||natural.isBlank()||bytes(natural)>4096||candidate.generation()<1||candidate.generation()>CanonicalJson.MAX_SAFE_INTEGER
                ||candidate.day()==null||candidate.revision()<0||candidate.revision()>CanonicalJson.MAX_SAFE_INTEGER
                ||!Set.of("news_dedup","news_value").contains(candidate.type())||!(Set.of("waiting_input","prepared","queued","running").contains(candidate.state())||"news_value".equals(candidate.type())&&Set.of("succeeded","no_input").contains(candidate.state()))
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
    private static IllegalStateException unavailable(){return new IllegalStateException("original source intent route unavailable");}
}
