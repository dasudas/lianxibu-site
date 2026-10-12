package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand.Position;
import com.lianxibu.site.repository.jobs.JdbcSourceDedupResults;
import com.lianxibu.site.service.sources.SourceRunAnalysisService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Commits the original final result, current source projection and intent terminal state atomically. */
public final class SourceValueFinalizationService {
    public record Committed(SourceValueFinalResult result,SourceValueFinalReceiptService.Stored receipt,Instant minimumObservationTime){
        public Committed {Objects.requireNonNull(result);Objects.requireNonNull(receipt);if(minimumObservationTime==null||minimumObservationTime.getNano()%1000!=0)throw invalid();}
        public String observationKeyJson(){
            var plan=FrozenSourceStagePlan.Reference.fromJson(new String(CanonicalJson.bytes(CanonicalJson.parse(result.json()).get("plan_ref")),java.nio.charset.StandardCharsets.UTF_8));var p=receipt.position();
            var key=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("schema_version","source-value-final-key-v1").put("owner_job_id",p.ownerId()).put("plan_hash",plan.planHash()).put("plan_index_hash",plan.indexHash()).put("result_hash",result.hash()).put("archive_index_hash",receipt.archiveRef().indexHash()).put("receipt_id",p.receiptId()).put("receipt_hash",CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(receipt.json()))).put("stage","news_value").put("generation",p.generation()).put("business_date",receipt.businessDate().toString()).put("run_id",receipt.runId()).put("source_id",receipt.sourceId());
            byte[] bytes=CanonicalJson.bytes(key);if(bytes.length>2048||!p.ownerId().equals(plan.ownerId())||!p.resultHash().equals(result.hash()))throw invalid();return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
        @Override public String toString(){return "CommittedFinalSourceValue[redacted]";}
    }
    private record Prepared(SourceValueFinalProofService.Committed proof,FrozenSourceValueFinal.Reference archive,SourceRunAnalysisCommand command,SourceValuePartialCommitPermitService.Captured permit){ }
    private final SourceValueFinalProofService proofs;private final SourceValueFinalArchiveService archives;private final FrozenSourceStagePlanService plans;private final FrozenSourceUniverseService universes;
    private final SourceValueFinalReceiptService receipts;private final SourceRunAnalysisService summaries;private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;private final SourceValuePartialCommitPermitService partialPermits;
    public SourceValueFinalizationService(SourceValueFinalProofService proofs,SourceValueFinalArchiveService archives,FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,SourceValueFinalReceiptService receipts,SourceRunAnalysisService summaries,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
        this(proofs,archives,plans,universes,receipts,summaries,writes,tx,clock,null);
    }
    public SourceValueFinalizationService(SourceValueFinalProofService proofs,SourceValueFinalArchiveService archives,FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,SourceValueFinalReceiptService receipts,SourceRunAnalysisService summaries,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock,SourceValuePartialCommitPermitService partialPermits){
        this.partialPermits=partialPermits;
        this.proofs=Objects.requireNonNull(proofs);this.archives=Objects.requireNonNull(archives);this.plans=Objects.requireNonNull(plans);this.universes=Objects.requireNonNull(universes);this.receipts=Objects.requireNonNull(receipts);this.summaries=Objects.requireNonNull(summaries);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public SourceValueFinalReceiptService.Stored finish(FrozenSourceStagePlan.Reference reference){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var staged=archives.stage(reference);if(staged==null)return null;
            var captured=reads.execute(s->prepare(reference,staged.archiveRef(),Objects.requireNonNull(proofs.readCommitted(reference))));var prepared=authorize(Objects.requireNonNull(captured));Instant raw=clock.instant();if(raw.isBefore(prepared.proof().minimumObservationTime()))throw invalid();Instant sealedAt=raw.truncatedTo(ChronoUnit.MICROS);var position=prepared.command().position();
            var stored=writes.execute(new SharedWriteExecutor.ActionContext(SourceValueFinalReceiptService.action(position.ownerId()),position.receiptId(),position.resultHash()),key->{var old=receipts.read(position.ownerId());if(old!=null)verify(old,prepared);return old;},context->{
                var old=receipts.read(position.ownerId());if(old!=null){verify(old,prepared);return old;}
                var cursor=receipts.begin(prepared.command());verifyPartial(prepared);var outcome=summaries.apply(prepared.command());boolean empty=prepared.permit()==null&&CanonicalJson.parse(prepared.proof().result().json()).get("eligible_count").asLong()==0;receipts.complete(cursor,empty,sealedAt);
                return receipts.append(SourceValueFinalReceiptService.Stored.create(prepared.command(),prepared.archive(),outcome,sealedAt));
            });
            if(prepared.permit()!=null){var committed=reads.execute(s->readCommitted(reference));if(committed==null||!committed.receipt().json().equals(stored.json()))throw invalid();}return stored;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public Committed readCommitted(FrozenSourceStagePlan.Reference reference){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);var proof=proofs.readCommitted(reference);if(proof==null)return null;var receipt=receipts.read(reference.ownerId());if(receipt==null)return null;
            var prepared=prepare(reference,receipt.archiveRef(),proof);verify(receipt,prepared);Instant minimum=proof.minimumObservationTime().isAfter(receipt.sealedAt())?proof.minimumObservationTime():receipt.sealedAt();return new Committed(proof.result(),receipt,minimum);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Prepared prepare(FrozenSourceStagePlan.Reference reference,FrozenSourceValueFinal.Reference archive,SourceValueFinalProofService.Committed proof){
        JdbcSourceDedupResults.requireReadOnly();var original=archives.verifyStaged(archive,proof.result());var plan=plans.readImmutable(reference);var universe=universes.readImmutable(plan.reference());var context=CanonicalJson.parse(SourceValueStageContext.readAny(plan.scope().stageContextJson(),plan.scope().businessDate(),plan.scope().cutoff()));boolean partial="source-dedup-dependency-v2".equals(WireValues.text(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version")));var dependency=partial?FrozenSourceDedupDependency.readPartial(context.get(FrozenSourceDedupDependency.FIELD),plan.scope().cutoff()):FrozenSourceDedupDependency.read(context.get(FrozenSourceDedupDependency.FIELD),plan.scope().cutoff());var p1=dependency.toJson().get("stage_result");
        var reports=new ArrayList<String>();for(var report:dependency.toJson().get("reports")){String summary=WireValues.text(report.get("summary"));if(!summary.isBlank())reports.add(summary);}
        String dedup=reports.isEmpty()?(WireValues.nonnegativeInteger(p1.get("eligible_count"))==0?"本来源没有本次待去重输入。":"本来源完成 "+WireValues.nonnegativeInteger(p1.get("eligible_count"))+" 条去重登记，未获得可用文字简报。"):String.join("\n\n",reports);
        if(partial&&reports.isEmpty()){long applied=WireValues.nonnegativeInteger(p1.get("applied_member_count"));dedup=applied==0?"本来源原计划包含 "+WireValues.nonnegativeInteger(p1.get("eligible_count"))+" 条去重输入，尚未获得已应用的去重结果。":"本来源完成 "+applied+" 条去重登记，未获得可用文字简报。";}
        if(original.missing().stream().anyMatch(m->Set.of("dedup_failed","dedup_cancelled","dedup_discarded","dedup_superseded").contains(m.reason())))dedup="本次来源去重覆盖不完整。\n\n"+dedup;
        if(original.missing().stream().anyMatch(m->"dedup_needs_review".equals(m.reason())))dedup="本次来源去重仍有待人工核验项。\n\n"+dedup;
        var position=new Position(reference.ownerId(),plan.logicalKey(),plan.generation(),plan.scope().cutoff(),original.hash(),SourceValueFinalReceiptService.receiptId(reference.ownerId()));
        var command=new SourceRunAnalysisCommand(universe.runId(),universe.sourceId(),plan.scope().businessDate(),WireValues.nonnegativeInteger(p1.get("generation")),universe.manifestHash(),universe.accepted(),universe.excluded(),position,dedup,original.summary(),original.coverageState());return new Prepared(proof,archive,command,null);
    }
    private Prepared authorize(Prepared candidate){
        if(!"source-value-final-result-v2".equals(WireValues.text(CanonicalJson.parse(candidate.proof().result().json()).get("schema_version"))))return candidate;
        if(partialPermits==null)throw invalid();var reference=FrozenSourceStagePlan.Reference.fromJson(new String(CanonicalJson.bytes(CanonicalJson.parse(candidate.proof().result().json()).get("plan_ref")),java.nio.charset.StandardCharsets.UTF_8));
        var permit=partialPermits.capture(reference);if(permit==null)throw invalid();matching(permit.proof(),candidate);return new Prepared(candidate.proof(),candidate.archive(),candidate.command(),permit);
    }
    private void verifyPartial(Prepared prepared){if(prepared.permit()!=null){if(partialPermits==null)throw invalid();matching(partialPermits.verify(prepared.permit()),prepared);}}
    private static void matching(SourceValueStageResult proof,Prepared finalResult){
        var result=CanonicalJson.parse(finalResult.proof().result().json());if(!proof.planRef().json().equals(new String(CanonicalJson.bytes(result.get("plan_ref")),java.nio.charset.StandardCharsets.UTF_8))||!proof.hash().equals(WireValues.sha256(result.get("whole_result_hash"))))throw invalid();
    }
    private static void verify(SourceValueFinalReceiptService.Stored receipt,Prepared original){
        if(receipt.sealedAt().isBefore(original.proof().minimumObservationTime()))throw invalid();var expected=SourceValueFinalReceiptService.Stored.create(original.command(),original.archive(),new SourceRunAnalysisService.Outcome(receipt.disposition(),receipt.projectionRevision()),receipt.sealedAt());if(!expected.json().equals(receipt.json()))throw invalid();
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original source value final commit unavailable");}
}
