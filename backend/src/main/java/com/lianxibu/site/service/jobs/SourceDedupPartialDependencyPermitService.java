package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Captures real immutable partial proof, then rechecks its authority in the caller's short writer. */
public final class SourceDedupPartialDependencyPermitService {
    public static final class Captured {
        private final SourceDedupPartialDependencyService.Proven original;private final SourceStageResolutionService.Snapshot snapshot;private final List<JdbcSourceDedupPartialBlocks.Part> blocks;
        private Captured(SourceDedupPartialDependencyService.Proven original,SourceStageResolutionService.Snapshot snapshot,List<JdbcSourceDedupPartialBlocks.Part> blocks){this.original=original;this.snapshot=snapshot;this.blocks=List.copyOf(blocks);}
        public FrozenSourceDedupDependency dependency(){return original.dependency();}
        @Override public String toString(){return "CapturedPartialSourceDedupDependency[redacted]";}
    }
    private final SourceDedupPartialDependencyService original;private final SourceStageResolutionService resolutions;private final JdbcSourceDedupPartialBlocks blocks;
    private final JdbcSourceDedupPartialReceipts receipts;private final JdbcSourceStageRoots roots;private final JdbcSourceStageAvailability observations;private final TransactionTemplate reads;
    public SourceDedupPartialDependencyPermitService(SourceDedupPartialDependencyService original,SourceStageResolutionService resolutions,JdbcSourceDedupPartialBlocks blocks,JdbcSourceDedupPartialReceipts receipts,JdbcSourceStageRoots roots,JdbcSourceStageAvailability observations,PlatformTransactionManager tx){
        this.original=Objects.requireNonNull(original);this.resolutions=Objects.requireNonNull(resolutions);this.blocks=Objects.requireNonNull(blocks);this.receipts=Objects.requireNonNull(receipts);this.roots=Objects.requireNonNull(roots);this.observations=Objects.requireNonNull(observations);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Captured capture(JsonNode profile,Instant cutoff){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var structural=FrozenSourceDedupDependency.readPartial(profile,cutoff);
            return reads.execute(status->{var proven=original.read(structural.toJson(),cutoff);var snapshot=resolutions.read(proven.seal().proof().planRef());
                if(!SourceDedupStageResult.createResolved(snapshot).json().equals(proven.seal().proof().json())||!SourceDedupPartialSealService.nativeHash(snapshot).equals(WireValues.sha256(CanonicalJson.parse(proven.seal().receiptJson()).get("native_capture_hash"))))throw invalid();
                var ref=proven.seal().archiveRef();var parts=blocks.parts(ref.ownerId(),JdbcSourceDedupPartialBlocks.stamp(ref.ownerId(),ref.indexHash()));FrozenSourceDedupPartial.decode(ref,parts.stream().map(JdbcSourceDedupPartialBlocks.Part::payload).toList(),proven.seal().proof());receipt(receipts.read(ref.ownerId()),proven);return new Captured(proven,snapshot,parts);
            });
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Successful return is valid only while the caller retains this writer's native locks. */
    public FrozenSourceDedupDependency verify(Captured captured,Instant cutoff){
        try {
            JdbcFrozenInputs.requireTx();Objects.requireNonNull(captured);var dependency=captured.dependency();if(cutoff==null||cutoff.getNano()%1000!=0||dependency.observedAt().isAfter(cutoff))throw invalid();var ref=captured.original.seal().archiveRef();
            receipts.lockOwner(ref.ownerId());roots.lockOriginal(captured.snapshot.fragments().stream().map(SourceStageResolutionService.Fragment::nativeRoot).toList());
            if(!captured.blocks.equals(blocks.parts(ref.ownerId(),JdbcSourceDedupPartialBlocks.stamp(ref.ownerId(),ref.indexHash()))))throw invalid();receipt(receipts.read(ref.ownerId()),captured.original);
            Instant observed=observations.read(dependency.observationKeyJson());if(!dependency.observedAt().equals(observed))throw invalid();return dependency;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(JdbcSourceStageRoots.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(RuntimeException rejected){throw invalid();}
    }
    private static void receipt(JdbcSourceDedupPartialReceipts.Raw raw,SourceDedupPartialDependencyService.Proven original){
        if(raw==null||raw.targetBytes()==null||raw.targetBytes()<1||raw.targetBytes()>2048||raw.payloadBytes()==null||raw.payloadBytes()<1||raw.payloadBytes()>8192)throw invalid();String owner=original.seal().archiveRef().ownerId();
        String target=JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",owner)),payload=original.seal().receiptJson();
        if(!JdbcSourceDedupPartialReceipts.receiptId(owner).equals(raw.id())||!"disposition".equals(raw.kind())||!JdbcSourceDedupPartialReceipts.TYPE.equals(raw.targetType())||!JdbcSourceDedupPartialReceipts.ACTOR.equals(raw.actor())||!JdbcSourceDedupPartialReceipts.REASON.equals(raw.reason())
                ||!target.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.target())))||!payload.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.payload())))||!Arrays.equals(raw.targetHash(),JdbcGenerationLineage.hash(target))||!Arrays.equals(raw.payloadHash(),JdbcGenerationLineage.hash(payload))||!Arrays.equals(raw.actionHash(),JdbcGenerationLineage.hash(JdbcSourceDedupPartialReceipts.action(owner)))||!WireValues.utcMicroseconds(CanonicalJson.parse(payload).get("sealed_at")).equals(raw.actedAt()))throw invalid();
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original partial source dedup writer proof unavailable");}
}
