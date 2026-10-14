package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.articles.EntityDedupService;
import com.lianxibu.site.service.articles.EntityDedupService.Automatic;
import com.lianxibu.site.service.articles.EntityDedupService.Decision;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One atomic frozen dedup batch; member failures must propagate to the outer writer. */
public final class DedupResultHandler implements Handler {
    public interface Downstream {
        void afterApplied(Input input,Validated result,List<Decision> decisions,Set<String> reanalyzeItems);
        default void afterCatalogApplied(Input input,Validated result,List<Decision> decisions,CatalogVersionBatchJudgmentWriter.Outcome versions){throw new Rejected();}
    }
    private final EntityDedupService entities;
    private final BiFunction<String,String,String> snapshots;
    private final Downstream downstream;
    private final CatalogVersionBatchPreflightService batches;
    private final CatalogVersionBatchJudgmentWriter versions;
    private final CatalogVersionBatchJudgmentWriter.Prepared prepared;
    private final Input preparedInput;private final Validated preparedResult;
    private final boolean preparedManual;
    public DedupResultHandler(EntityDedupService entities,BiFunction<String,String,String> snapshots,Downstream downstream) {
        this(entities,snapshots,downstream,null,null,null,null,null,false);
    }
    /** Explicit native ports for new catalog v2 inputs; construction performs no IO. */
    public DedupResultHandler(EntityDedupService entities,BiFunction<String,String,String> snapshots,Downstream downstream,
            CatalogVersionBatchPreflightService batches,CatalogVersionBatchJudgmentWriter versions){
        this(entities,snapshots,downstream,Objects.requireNonNull(batches),Objects.requireNonNull(versions),null,null,null,false);
    }
    private DedupResultHandler(EntityDedupService entities,BiFunction<String,String,String> snapshots,Downstream downstream,
            CatalogVersionBatchPreflightService batches,CatalogVersionBatchJudgmentWriter versions,
            CatalogVersionBatchJudgmentWriter.Prepared prepared,Input input,Validated result,boolean manual){
        this.entities=Objects.requireNonNull(entities);this.snapshots=Objects.requireNonNull(snapshots);this.downstream=Objects.requireNonNull(downstream);
        this.batches=batches;this.versions=versions;this.prepared=prepared;preparedInput=input;preparedResult=result;preparedManual=manual;
    }
    @Override public Handler prepare(Input input,Validated result){
        return prepare(input,result,false);
    }
    private Handler prepare(Input input,Validated result,boolean manual){
        if(input==null||result==null)throw new Rejected();if(!"2".equals(input.dedupResultVersion()))return this;
        if(batches==null||versions==null||TransactionSynchronizationManager.isActualTransactionActive())throw new Rejected();
        var batch=batches.capture(input);var value=versions.prepare(batch,result);
        return new DedupResultHandler(entities,snapshots,downstream,batches,versions,value,input,result,manual);
    }
    /** Preparation alone grants no owner or root application permission. */
    public Handler prepareForManual(Input input,Validated result){
        if(input==null||!"2".equals(input.dedupResultVersion()))throw new Rejected();
        return prepare(input,result,true);
    }
    @Override public Applied apply(Input input,Validated result,Current current,boolean manual) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("dedup result requires shared write transaction");
        if(input==null||result==null||current==null||!input.stage().dedup()||!"dedup".equals(result.kind())||!current.dependenciesValid())throw new Rejected();
        var snapshot=CanonicalJson.parse(snapshots.apply(input.rootId(),input.inputHash()));
        var members=FrozenMemberBindings.read(snapshot,input);
        // Revalidate the envelope so this port cannot accept a forged Validated instance.
        var envelope=JsonMapper.shared().createObjectNode();envelope.put("schema_version","1");envelope.put("input_hash",result.inputHash());envelope.put("result_hash",result.resultHash());envelope.set("payload",CanonicalJson.parse(result.payloadJson()));
        if(!result.equals(ResultApplicationService.validate(input,envelope.toString())))throw new Rejected();
        CatalogVersionBatchJudgmentWriter.Outcome versionOutcome=null;
        if("2".equals(input.dedupResultVersion())){
            if(manual!=preparedManual||versions==null||prepared==null||!input.equals(preparedInput)||!result.equals(preparedResult))throw new Rejected();
            // All native version proofs precede member ownership/count mutations.
            versionOutcome=versions.applyInTransaction(prepared);
        }
        List<JsonNode> values=new ArrayList<>();envelope.get("payload").get("items").forEach(values::add);
        values.sort((a,b)->Arrays.compareUnsigned(a.get("item_id").asText().getBytes(StandardCharsets.UTF_8),b.get("item_id").asText().getBytes(StandardCharsets.UTF_8)));
        List<Decision> decisions=new ArrayList<>();Set<String> reanalyze=new HashSet<>();
        for(var value:values) {
            String id=value.get("item_id").asText();var member=members.get(id);
            var command=new Automatic(id,member.expectedRevision(),value.get("decision").asText(),member.category(),member.identityKey(),
                value.get("candidate_entity_id").isNull()?null:value.get("candidate_entity_id").asText(),input.candidatesByItem().getOrDefault(id,Set.of()).stream().sorted().toList(),
                value.get("suggested_title").asText(),value.get("suggested_summary").isNull()?null:value.get("suggested_summary").asText(),value.get("reason").asText());
            var identity=JsonMapper.shared().createObjectNode();identity.put("scope","dedup-member-v1");identity.put("root_id",input.rootId());identity.put("result_hash",result.resultHash());identity.put("item_id",id);
            String key=CanonicalJson.hash(CanonicalJson.Domain.ACTION,identity);
            decisions.add(entities.applyAutomaticInTransaction(command,new SharedWriteExecutor.ActionContext(key,member.newEntityId(),input.inputHash())));
            if(value.get("reanalyze_recommended").booleanValue())reanalyze.add(id);
        }
        if(versionOutcome==null)downstream.afterApplied(input,result,List.copyOf(decisions),Set.copyOf(reanalyze));
        else downstream.afterCatalogApplied(input,result,List.copyOf(decisions),versionOutcome);
        return new Applied(null,null);
    }
}
