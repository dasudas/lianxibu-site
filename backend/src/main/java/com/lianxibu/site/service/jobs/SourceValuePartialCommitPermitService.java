package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Captures actual sealed partial P2 authority and rechecks it in the caller's short writer. */
public final class SourceValuePartialCommitPermitService {
    public static final class Captured {
        private final SourceValuePartialSealService.Committed seal;private final SourceStageResolutionService.Snapshot snapshot;
        private final List<JdbcSourceValuePartialBlocks.Part> blocks;private final SourceDedupPartialDependencyPermitService.Captured dedup;
        private Captured(SourceValuePartialSealService.Committed seal,SourceStageResolutionService.Snapshot snapshot,List<JdbcSourceValuePartialBlocks.Part> blocks,SourceDedupPartialDependencyPermitService.Captured dedup){this.seal=seal;this.snapshot=snapshot;this.blocks=List.copyOf(blocks);this.dedup=dedup;}
        public SourceValueStageResult proof(){return seal.proof();}
        @Override public String toString(){return "CapturedPartialSourceValueCommit[redacted]";}
    }
    private final SourceValuePartialSealService seals;private final SourceStageResolutionService resolutions;private final JdbcSourceValuePartialBlocks blocks;
    private final JdbcSourceValuePartialReceipts receipts;private final JdbcSourceStageRoots roots;private final SourceDedupPartialDependencyPermitService dedup;private final TransactionTemplate reads;
    public SourceValuePartialCommitPermitService(SourceValuePartialSealService seals,SourceStageResolutionService resolutions,JdbcSourceValuePartialBlocks blocks,JdbcSourceValuePartialReceipts receipts,JdbcSourceStageRoots roots,SourceDedupPartialDependencyPermitService dedup,PlatformTransactionManager tx){
        this.seals=Objects.requireNonNull(seals);this.resolutions=Objects.requireNonNull(resolutions);this.blocks=Objects.requireNonNull(blocks);this.receipts=Objects.requireNonNull(receipts);this.roots=Objects.requireNonNull(roots);this.dedup=dedup;reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Captured capture(FrozenSourceStagePlan.Reference reference){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Objects.requireNonNull(reference);var captured=reads.execute(s->material(reference));if(captured==null)return null;
            var scope=captured.snapshot.plan().scope();var context=CanonicalJson.parse(SourceValueStageContext.readAny(scope.stageContextJson(),scope.businessDate(),scope.cutoff()));
            if(!"source-dedup-dependency-v2".equals(WireValues.text(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version"))))return captured;
            if(dedup==null)throw invalid();var p1=dedup.capture(context.get(FrozenSourceDedupDependency.FIELD),scope.cutoff());
            if(p1==null||!p1.dependency().json().equals(JdbcFrozenInputs.canonical(context.get(FrozenSourceDedupDependency.FIELD))))throw invalid();
            return new Captured(captured.seal,captured.snapshot,captured.blocks,p1);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Successful return is valid only while the caller retains this writer's native locks. */
    public SourceValueStageResult verify(Captured captured){
        try {
            JdbcFrozenInputs.requireTx();Objects.requireNonNull(captured);var ref=captured.seal.archiveRef();receipts.lockOwner(ref.ownerId());
            if(captured.snapshot.fragments().isEmpty()){if(!captured.snapshot.plan().chunks().isEmpty()||captured.dedup==null)throw invalid();}
            else roots.lockOriginal(captured.snapshot.fragments().stream().map(SourceStageResolutionService.Fragment::nativeRoot).toList());
            if(!captured.blocks.equals(blocks.parts(ref.ownerId(),JdbcSourceValuePartialBlocks.stamp(ref.ownerId(),ref.indexHash()))))throw invalid();receipt(receipts.read(ref.ownerId()),captured.seal);
            var scope=captured.snapshot.plan().scope();var context=CanonicalJson.parse(SourceValueStageContext.readAny(scope.stageContextJson(),scope.businessDate(),scope.cutoff()));
            if("source-dedup-dependency-v2".equals(WireValues.text(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version")))){
                if(dedup==null||captured.dedup==null||!dedup.verify(captured.dedup,scope.cutoff()).json().equals(JdbcFrozenInputs.canonical(context.get(FrozenSourceDedupDependency.FIELD))))throw invalid();
            }else if(captured.dedup!=null)throw invalid();
            return captured.proof();
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(JdbcSourceStageRoots.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(RuntimeException rejected){throw invalid();}
    }
    private Captured material(FrozenSourceStagePlan.Reference reference){
        var seal=seals.readCommitted(reference);if(seal==null)return null;var snapshot=resolutions.read(reference);var original=SourceValueStageResult.createResolved(snapshot);
        if(!original.json().equals(seal.proof().json())||!SourceValuePartialSealService.nativeHash(snapshot).equals(WireValues.sha256(CanonicalJson.parse(seal.receiptJson()).get("native_capture_hash"))))throw invalid();
        var ref=seal.archiveRef();var parts=blocks.parts(ref.ownerId(),JdbcSourceValuePartialBlocks.stamp(ref.ownerId(),ref.indexHash()));FrozenSourceValuePartial.decode(ref,parts.stream().map(JdbcSourceValuePartialBlocks.Part::payload).toList(),seal.proof());receipt(receipts.read(ref.ownerId()),seal);return new Captured(seal,snapshot,parts,null);
    }
    private static void receipt(JdbcSourceValuePartialReceipts.Raw raw,SourceValuePartialSealService.Committed original){
        if(raw==null||raw.targetBytes()==null||raw.targetBytes()<1||raw.targetBytes()>2048||raw.payloadBytes()==null||raw.payloadBytes()<1||raw.payloadBytes()>8192)throw invalid();String owner=original.archiveRef().ownerId();
        String target=JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",owner)),payload=original.receiptJson();
        if(!JdbcSourceValuePartialReceipts.receiptId(owner).equals(raw.id())||!"disposition".equals(raw.kind())||!JdbcSourceValuePartialReceipts.TYPE.equals(raw.targetType())||!JdbcSourceValuePartialReceipts.ACTOR.equals(raw.actor())||!JdbcSourceValuePartialReceipts.REASON.equals(raw.reason())
                ||!target.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.target())))||!payload.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.payload())))||!Arrays.equals(raw.targetHash(),JdbcGenerationLineage.hash(target))||!Arrays.equals(raw.payloadHash(),JdbcGenerationLineage.hash(payload))||!Arrays.equals(raw.actionHash(),JdbcGenerationLineage.hash(JdbcSourceValuePartialReceipts.action(owner)))||!WireValues.utcMicroseconds(CanonicalJson.parse(payload).get("sealed_at")).equals(raw.actedAt()))throw invalid();
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original partial source value writer proof unavailable");}
}
