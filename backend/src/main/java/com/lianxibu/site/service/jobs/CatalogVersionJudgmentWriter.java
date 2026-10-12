package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EntityAnalysisStateService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins the original result writer. The caller must have validated the original retained model result. */
public final class CatalogVersionJudgmentWriter {
    private final CatalogVersionCommitPermitService permits;private final EntityAnalysisStateService entities;
    private final JdbcCatalogVersionJudgments store;private final CatalogVersionJudgmentLedger ledger;
    private final JdbcFrozenSourcePlans owners;private final JdbcJobRecords records;private final String category;private final Clock clock;
    public CatalogVersionJudgmentWriter(DataSource source,CatalogVersionCommitPermitService permits,EntityAnalysisStateService entities,String category,Clock clock){
        Objects.requireNonNull(source);this.permits=Objects.requireNonNull(permits);this.entities=Objects.requireNonNull(entities);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};this.clock=Objects.requireNonNull(clock);store=new JdbcCatalogVersionJudgments(source);ledger=new CatalogVersionJudgmentLedger(store,category);owners=new JdbcFrozenSourcePlans(source);records=new JdbcJobRecords(source);
    }
    /** Original permanent receipt wins even after qualification changes or a model suggests something different. */
    public CatalogVersionJudgmentLedger.Stored committed(String key){return (TransactionSynchronizationManager.isCurrentTransactionReadOnly()?ledger.read(key):ledger.lockAndRead(key)).orElse(null);}
    public CatalogVersionJudgmentLedger.Stored applyInTransaction(CatalogVersionJudgmentValue.Value value,CatalogVersionCommitPermitService.Captured captured){
        try{
            JdbcFrozenInputs.requireTx();Objects.requireNonNull(value);if(!category.equals(value.category()))throw invalid();var previous=ledger.lockAndRead(value.judgmentKey());if(previous.isPresent())return previous.orElseThrow();
            var p=permits.verify(captured);var v=CanonicalJson.parse(value.json());if(!p.hash().equals(WireValues.text(v.get("projection_hash")))||!p.judgmentKey().equals(value.judgmentKey())||!p.entityId().equals(value.entityId())||!p.memberId().equals(value.memberId()))throw invalid();
            var proof=p.toJson();Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);if(now.isBefore(WireValues.utcMicroseconds(proof.get("cutoff"))))throw invalid();Long generation=null;String intent=null;
            if(value.reanalyzeRecommended()){
                var root=owners.lockOwner(p.rootId());if(!"logical_task".equals(root.kind())||!(category+"_analysis").equals(root.type())||root.key()==null||root.key().isBlank()||!Arrays.equals(root.keyHash(),JdbcGenerationLineage.hash(root.key()))||root.generation()!=WireValues.nonnegativeInteger(proof.get("generation")))throw invalid();
                var state=entities.reserve(p.entityId(),category,WireValues.nonnegativeInteger(proof.get("entity_revision")),WireValues.nonnegativeInteger(proof.get("entity_generation")));generation=state.generation();intent=JdbcFrozenInputs.stableId("intent",root.key()+":"+generation);String actual=records.ensureIntent(new JdbcJobRecords.Intent(intent,root.key(),generation,category+"_analysis",captured.businessDate()),now);if(!intent.equals(actual))throw invalid();
            }
            store.append(value.judgmentKey(),CatalogVersionJudgmentLedger.receipt(value,generation,intent,now),now);var stored=ledger.lockAndRead(value.judgmentKey()).orElseThrow();if(!value.hash().equals(stored.judgmentHash()))throw invalid();return stored;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("catalog version first judgment writer unavailable");}
}
