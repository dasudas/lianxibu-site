package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EntityAnalysisStateService;
import com.lianxibu.site.service.jobs.ResultApplicationService.Validated;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins the original result transaction; preparation never grants model or write authority. */
public final class CatalogVersionBatchJudgmentWriter {
    public static final class Prepared {
        private final Object issuer;
        private final CatalogVersionBatchPreflightService.Batch batch;
        private final Map<String,String> keys;
        private final Map<String,CatalogVersionJudgmentValue.Value> values;
        private Prepared(Object issuer,CatalogVersionBatchPreflightService.Batch batch,
                Map<String,String> keys,Map<String,CatalogVersionJudgmentValue.Value> values){
            this.issuer=issuer;this.batch=batch;this.keys=Map.copyOf(keys);this.values=Map.copyOf(values);
        }
        @Override public String toString(){return "PreparedCatalogVersionBatch[redacted]";}
    }
    public record Outcome(Map<String,CatalogVersionJudgmentLedger.Stored> judgments){
        public Outcome{judgments=Map.copyOf(judgments);}
        public Set<String> reanalysisItems(){
            var items=new HashSet<String>();judgments.forEach((id,value)->{if("reanalyze".equals(value.verdict()))items.add(id);});return Set.copyOf(items);
        }
        @Override public String toString(){return "CatalogVersionBatchOutcome[redacted]";}
    }
    private record Allocation(Long generation,String intent){ }
    private final Object issuer=new Object();
    private final CatalogVersionCommitPermitService permits;
    private final CatalogVersionGenerationAllocationService allocator;
    private final JdbcCatalogVersionJudgments storage;
    private final CatalogVersionJudgmentLedger ledger;
    private final String category;
    private final Clock clock;

    public CatalogVersionBatchJudgmentWriter(DataSource source,CatalogVersionCommitPermitService permits,
            EntityAnalysisStateService entities,String category,Clock clock){
        Objects.requireNonNull(source);this.permits=Objects.requireNonNull(permits);Objects.requireNonNull(entities);
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
        this.clock=Objects.requireNonNull(clock);storage=new JdbcCatalogVersionJudgments(source);
        ledger=new CatalogVersionJudgmentLedger(storage,category);allocator=new CatalogVersionGenerationAllocationService(source,permits,entities,category);
    }
    public Prepared prepare(CatalogVersionBatchPreflightService.Batch batch,Validated result){
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Objects.requireNonNull(batch);
            if(!(category+"_dedup").equals(batch.input().stage().name().toLowerCase(Locale.ROOT)))throw invalid();
            var projections=new HashMap<String,CatalogVersionQualificationProjection.Projection>();
            batch.permissions().forEach((id,c)->projections.put(id,c.projection()));
            var values=CatalogVersionJudgmentValue.createAll(projections,batch.input(),result);
            var keys=new HashMap<String,String>();
            for(var item:CanonicalJson.parse(result.payloadJson()).get("items")){
                if(item.get("version_changed").isNull())continue;
                String id=item.get("item_id").asText();var entry=batch.frozen().entries().get(id);if(entry==null)throw invalid();
                if(!entry.entityId().equals(item.get("candidate_entity_id").asText()))throw invalid();
                if(!values.containsKey(id)&&!batch.committed().containsKey(id))throw invalid();
                keys.put(id,entry.judgmentKey());
            }
            if(!keys.keySet().containsAll(values.keySet())||keys.size()>100)throw invalid();
            return new Prepared(issuer,batch,keys,values);
        }catch(RuntimeException rejected){throw invalid();}
    }
    /** Complete first-ledger read, suitable for the original shared writer's receipt lookup. */
    public Outcome committed(Prepared prepared){
        requirePrepared(prepared);var byKey=lookup(prepared);if(byKey.size()!=new HashSet<>(prepared.keys.values()).size())return null;
        return outcome(prepared,byKey);
    }
    public Outcome applyInTransaction(Prepared prepared){
        try{
            JdbcFrozenInputs.requireTx();if(!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
            requirePrepared(prepared);var byKey=lookup(prepared);
            var fresh=new TreeMap<String,CatalogVersionCommitPermitService.Captured>();
            var first=new TreeMap<String,CatalogVersionJudgmentValue.Value>();
            // Stable member order chooses one original recommendation when duplicate keys disagree.
            for(String id:prepared.keys.keySet().stream().sorted((a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8))).toList()){
                String key=prepared.keys.get(id);if(byKey.containsKey(key))continue;
                var c=prepared.batch.permissions().get(id);var value=prepared.values.get(id);if(c==null||value==null)throw invalid();
                fresh.put(id,c);first.putIfAbsent(key,value);
            }
            var verified=permits.verifyAll(fresh.values());
            for(var entry:fresh.entrySet()){
                var p=verified.get(entry.getKey());var value=prepared.values.get(entry.getKey());
                if(p==null||!p.judgmentKey().equals(value.judgmentKey())||!p.entityId().equals(value.entityId())
                        ||!p.hash().equals(CanonicalJson.parse(value.json()).get("projection_hash").asText()))throw invalid();
            }
            Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
            for(var p:verified.values())if(now.isBefore(WireValues.utcMicroseconds(p.toJson().get("cutoff"))))throw invalid();
            var positive=new TreeMap<String,List<CatalogVersionJudgmentValue.Value>>();
            for(var value:first.values())if(value.reanalyzeRecommended())positive.computeIfAbsent(value.entityId(),id->new ArrayList<>()).add(value);
            var allocations=new HashMap<String,Allocation>();
            for(var group:positive.entrySet()){
                var value=group.getValue().getFirst();var p=verified.get(value.memberId());var proof=p.toJson();
                for(var other:group.getValue()){
                    var q=verified.get(other.memberId()).toJson();
                    for(String name:List.of("root_id","content_id","generation"))if(!proof.get(name).equals(q.get(name)))throw invalid();
                    var a=fresh.get(value.memberId());var b=fresh.get(other.memberId());
                    if(!a.businessDate().equals(b.businessDate())||a.entityRevision()!=b.entityRevision()||a.entityGeneration()!=b.entityGeneration()||!a.entityState().equals(b.entityState()))throw invalid();
                }
                var allocated=allocator.allocate(fresh.get(value.memberId()),now);
                allocations.put(group.getKey(),new Allocation(allocated.generation(),allocated.intentId()));
            }
            for(var value:first.values()){
                var allocation=value.reanalyzeRecommended()?allocations.get(value.entityId()):new Allocation(null,null);
                storage.append(value.judgmentKey(),CatalogVersionJudgmentLedger.receipt(value,allocation.generation(),allocation.intent(),now),now);
                var stored=ledger.lockAndRead(value.judgmentKey()).orElseThrow();if(!value.hash().equals(stored.judgmentHash()))throw invalid();byKey.put(value.judgmentKey(),stored);
            }
            return outcome(prepared,byKey);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Map<String,CatalogVersionJudgmentLedger.Stored> lookup(Prepared prepared){
        var values=new HashMap<String,CatalogVersionJudgmentLedger.Stored>();
        for(String key:new TreeSet<>(prepared.keys.values())){
            var previous=TransactionSynchronizationManager.isCurrentTransactionReadOnly()?ledger.read(key):ledger.lockAndRead(key);
            previous.ifPresent(value->values.put(key,value));
        }
        for(var entry:prepared.keys.entrySet()){
            var existing=values.get(entry.getValue());var original=prepared.batch.committed().get(entry.getKey());
            if(existing!=null&&!prepared.batch.frozen().entries().get(entry.getKey()).entityId().equals(existing.entityId()))throw invalid();
            if(original!=null&&(existing==null||!original.json().equals(existing.json())))throw invalid();
        }
        return values;
    }
    private Outcome outcome(Prepared prepared,Map<String,CatalogVersionJudgmentLedger.Stored> byKey){
        var values=new HashMap<String,CatalogVersionJudgmentLedger.Stored>();prepared.keys.forEach((id,key)->values.put(id,Objects.requireNonNull(byKey.get(key))));return new Outcome(values);
    }
    private void requirePrepared(Prepared prepared){if(prepared==null||prepared.issuer!=issuer)throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog version batch judgment writer unavailable");}
}
