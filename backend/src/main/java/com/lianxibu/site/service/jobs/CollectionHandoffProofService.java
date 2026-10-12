package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import com.lianxibu.site.service.sources.CollectionHandoffService.Facts;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A real collection dependency is a terminal handoff, never a fabricated AI root. */
public final class CollectionHandoffProofService {
    public record Proof(String intentId,String dispositionId,String payloadHash,Facts facts) { }
    private final JdbcEntityDecisions receipts;private final JdbcCollectionProofs intents;private final CollectionHandoffService sources;
    public CollectionHandoffProofService(DataSource source,CollectionHandoffService sources){this.receipts=new JdbcEntityDecisions(source);this.intents=new JdbcCollectionProofs(source);this.sources=Objects.requireNonNull(sources);}
    public Proof read(String runId){
        JdbcFrozenInputs.requireTx();WireValues.id(runId,false);
        String action="collection-handoff:"+runId,id=JdbcFrozenInputs.stableId("collection-handoff",action);
        var receipt=receipts.read(id,action);
        if(receipt==null||!id.equals(receipt.id())||!"collection_handoff".equals(receipt.targetType())||!"collection-worker".equals(receipt.operatorId()))throw unavailable();
        var payload=CanonicalJson.parse(receipt.payloadJson());closed(payload,"schema_version","intent_id","facts");
        if(!"collection-handoff-v1".equals(WireValues.text(payload.get("schema_version"))))throw unavailable();
        var terminal=sources.terminalFacts(runId);var facts=terminal.facts();
        // Reconstruct from the actual validated source facts; coercion cannot manufacture proof.
        if(!Arrays.equals(CanonicalJson.bytes(JsonMapper.shared().valueToTree(facts)),CanonicalJson.bytes(payload.get("facts"))))throw unavailable();
        String stage=stage(facts);
        String key=logicalKey(facts),intentId=JdbcFrozenInputs.stableId("intent",key+":"+facts.generation());
        var target=CanonicalJson.parse(receipt.targetKeyJson());closed(target,"kind","id");
        if(!"generation_intent".equals(WireValues.text(target.get("kind")))||!intentId.equals(WireValues.id(target.get("id"),false))||!intentId.equals(WireValues.id(payload.get("intent_id"),false))||!terminal.jobRefs().contains(intentId))throw unavailable();
        var intent=intents.read(intentId);
        if(intent==null||!intentId.equals(intent.id())||!key.equals(intent.logicalKey())||!Arrays.equals(JdbcGenerationLineage.hash(key),intent.hash())||facts.generation()!=intent.generation()||!stage.equals(intent.stage())||!facts.businessDate().equals(intent.businessDate()))throw unavailable();
        return new Proof(intentId,id,HexFormat.of().formatHex(JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(payload))),facts);
    }
    static String stage(Facts facts){return (facts.category().equals("news_tech")?"news":facts.category())+"_dedup";}
    static String logicalKey(Facts facts){
        var natural=JsonMapper.shared().createObjectNode();natural.put("source_id",facts.sourceId());natural.put("run_kind",facts.runKind());
        natural.put("business_date",facts.businessDate().toString());natural.put("stage",stage(facts));
        return "collection:"+CanonicalJson.hash(CanonicalJson.Domain.ACTION,natural);
    }
    private static void closed(JsonNode n,String... names){var fields=Set.of(names);WireValues.object(n,fields,fields);}
    private static IllegalStateException unavailable(){return new IllegalStateException("collection handoff proof unavailable");}
}
