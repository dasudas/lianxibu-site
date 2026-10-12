package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand.Position;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.SourceRunAnalysisService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Applies the original catalog summary, intent state and receipt in one native writer. */
public final class CatalogSourceFinalizationService {
    public record Committed(CatalogSourceFinalResult result,CatalogSourceFinalReceiptService.Stored receipt,Instant minimumObservationTime) {
        public Committed {Objects.requireNonNull(result);Objects.requireNonNull(receipt);if(minimumObservationTime==null||minimumObservationTime.getNano()%1000!=0)throw invalid();}
        public String observationKeyJson(){
            var plan=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(CanonicalJson.parse(result.json()).get("plan_ref")));var p=receipt.position();
            var key=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("schema_version","catalog-source-final-key-v1").put("category",receipt.category()).put("owner_job_id",p.ownerId()).put("plan_hash",plan.planHash()).put("plan_index_hash",plan.indexHash()).put("result_hash",result.hash()).put("archive_index_hash",receipt.archiveRef().indexHash()).put("receipt_id",p.receiptId()).put("receipt_hash",CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(receipt.json()))).put("stage",receipt.category()+"_dedup").put("generation",p.generation()).put("business_date",receipt.businessDate().toString()).put("run_id",receipt.runId()).put("source_id",receipt.sourceId());
            byte[] bytes=CanonicalJson.bytes(key);if(bytes.length>2048||!p.ownerId().equals(plan.ownerId())||!p.resultHash().equals(result.hash()))throw invalid();return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
        @Override public String toString(){return "CommittedCatalogSourceFinal[redacted]";}
    }
    private record Prepared(CatalogSourceFinalProofService.Committed proof,FrozenCatalogSourceFinal.Reference archive,SourceRunAnalysisCommand command) { }
    private final CatalogSourceFinalProofService proofs;private final CatalogSourceFinalArchiveService archives;private final FrozenSourceStagePlanService plans;private final FrozenSourceUniverseService universes;
    private final CatalogSourceFinalReceiptService receipts;private final SourceRunAnalysisService summaries;private final SharedWriteExecutor writes;private final CatalogSourceFinalCommitPermitService permits;private final TransactionTemplate reads;private final Clock clock;private final String category;
    public CatalogSourceFinalizationService(CatalogSourceFinalProofService proofs,CatalogSourceFinalArchiveService archives,FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,CatalogSourceFinalReceiptService receipts,SourceRunAnalysisService summaries,SharedWriteExecutor writes,CatalogSourceFinalCommitPermitService permits,PlatformTransactionManager tx,Clock clock,String category){
        this.proofs=Objects.requireNonNull(proofs);this.archives=Objects.requireNonNull(archives);this.plans=Objects.requireNonNull(plans);this.universes=Objects.requireNonNull(universes);this.receipts=Objects.requireNonNull(receipts);this.summaries=Objects.requireNonNull(summaries);this.writes=Objects.requireNonNull(writes);this.permits=Objects.requireNonNull(permits);this.clock=Objects.requireNonNull(clock);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public CatalogSourceFinalReceiptService.Stored finish(FrozenSourceStagePlan.Reference reference){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Objects.requireNonNull(reference);
            var existing=reads.execute(s->readCommitted(reference));if(existing!=null)return existing.receipt();
            var staged=archives.stage(reference);if(staged==null)return null;
            var prepared=reads.execute(s->prepare(reference,staged.archiveRef(),Objects.requireNonNull(proofs.readCommitted(reference))));var captured=permits.capture(reference,staged.archiveRef());if(captured==null)return null;matching(captured.proof(),prepared);
            Instant raw=clock.instant();if(raw.isBefore(prepared.proof().minimumObservationTime()))throw invalid();Instant sealedAt=raw.truncatedTo(ChronoUnit.MICROS);var position=prepared.command().position();
            return writes.execute(new SharedWriteExecutor.ActionContext(CatalogSourceFinalReceiptService.action(position.ownerId()),position.receiptId(),position.resultHash()),key->{var old=receipts.read(position.ownerId());if(old!=null)verify(old,prepared);return old;},context->{
                var old=receipts.read(position.ownerId());if(old!=null){verify(old,prepared);return old;}
                var cursor=receipts.begin(prepared.command());matching(permits.verify(captured),prepared);var outcome=summaries.apply(prepared.command());boolean empty=CanonicalJson.parse(prepared.proof().result().json()).get("eligible_count").asLong()==0;receipts.complete(cursor,empty,sealedAt);
                return receipts.append(CatalogSourceFinalReceiptService.Stored.create(prepared.command(),prepared.archive(),outcome,sealedAt,category));
            });
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public Committed readCommitted(FrozenSourceStagePlan.Reference reference){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);var proof=proofs.readCommitted(reference);if(proof==null)return null;var receipt=receipts.read(reference.ownerId());if(receipt==null)return null;var prepared=prepare(reference,receipt.archiveRef(),proof);verify(receipt,prepared);Instant minimum=proof.minimumObservationTime().isAfter(receipt.sealedAt())?proof.minimumObservationTime():receipt.sealedAt();return new Committed(proof.result(),receipt,minimum);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Prepared prepare(FrozenSourceStagePlan.Reference reference,FrozenCatalogSourceFinal.Reference archive,CatalogSourceFinalProofService.Committed proof){
        JdbcSourceDedupResults.requireReadOnly();var original=archives.verifyStaged(archive,proof.result());var plan=plans.readImmutable(reference);var universe=universes.readImmutable(plan.reference());
        if(!category.concat("_dedup").equals(plan.stage().name().toLowerCase(Locale.ROOT))||!category.equals(CanonicalJson.parse(original.json()).get("category").asText()))throw invalid();
        var collection=FrozenCollectionManifest.read(CanonicalJson.parse(original.json()).get(FrozenCollectionManifest.FIELD));if(collection.size()!=1)throw invalid();var facts=collection.getFirst().facts();
        var position=new Position(reference.ownerId(),plan.logicalKey(),plan.generation(),plan.scope().cutoff(),original.hash(),CatalogSourceFinalReceiptService.receiptId(reference.ownerId()));
        return new Prepared(proof,archive,new SourceRunAnalysisCommand(universe.runId(),universe.sourceId(),plan.scope().businessDate(),facts.generation(),universe.manifestHash(),universe.accepted(),universe.excluded(),position,original.summary(),"",original.coverageState()));
    }
    private static void matching(CatalogSourceFinalProofService.Committed proof,Prepared prepared){if(proof==null||!proof.result().json().equals(prepared.proof().result().json())||!proof.minimumObservationTime().equals(prepared.proof().minimumObservationTime()))throw invalid();}
    private void verify(CatalogSourceFinalReceiptService.Stored receipt,Prepared original){
        if(!category.equals(receipt.category())||receipt.sealedAt().isBefore(original.proof().minimumObservationTime()))throw invalid();var expected=CatalogSourceFinalReceiptService.Stored.create(original.command(),original.archive(),new SourceRunAnalysisService.Outcome(receipt.disposition(),receipt.projectionRevision()),receipt.sealedAt(),category);if(!expected.json().equals(receipt.json()))throw invalid();
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog source final commit unavailable");}
}
