package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Atomically seals original partial P2 proofs. Observation, parent handoff and finalization are separate. */
public final class SourceValuePartialSealService {
    public static final class Committed {
        private final SourceValueStageResult proof;private final FrozenSourceValuePartial.Reference archive;private final String receipt;private final Instant minimum;
        private Committed(SourceValueStageResult proof,FrozenSourceValuePartial.Reference archive,String receipt,Instant minimum){this.proof=proof;this.archive=archive;this.receipt=receipt;this.minimum=minimum;}
        public SourceValueStageResult proof(){return proof;}public FrozenSourceValuePartial.Reference archiveRef(){return archive;}public String receiptJson(){return receipt;}public Instant minimumObservationTime(){return minimum;}
        @Override public String toString(){return "CommittedSourceValuePartial[redacted]";}
    }
    private record Prepared(SourceStageResolutionService.Snapshot snapshot,SourceValueStageResult proof,FrozenSourceValuePartial.Reference archive,String nativeHash,Instant minimum,SourceDedupPartialDependencyPermitService.Captured permit){ }
    private final SourceStageResolutionService resolutions;private final SourceValuePartialArchiveService archives;private final JdbcSourceValuePartialReceipts store;private final JdbcSourceStageRoots roots;
    private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;private final SourceDedupPartialDependencyPermitService partialPermits;
    public SourceValuePartialSealService(SourceStageResolutionService resolutions,SourceValuePartialArchiveService archives,JdbcSourceValuePartialReceipts store,JdbcSourceStageRoots roots,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){this(resolutions,archives,store,roots,writes,tx,clock,null);}
    public SourceValuePartialSealService(SourceStageResolutionService resolutions,SourceValuePartialArchiveService archives,JdbcSourceValuePartialReceipts store,JdbcSourceStageRoots roots,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock,SourceDedupPartialDependencyPermitService partialPermits){
        this.partialPermits=partialPermits;this.resolutions=Objects.requireNonNull(resolutions);this.archives=Objects.requireNonNull(archives);this.store=Objects.requireNonNull(store);this.roots=Objects.requireNonNull(roots);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Committed seal(FrozenSourceStagePlan.Reference reference){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Objects.requireNonNull(reference);var previous=reads.execute(s->readCommitted(reference));if(previous!=null)return previous;
            var staged=archives.stage(reference);if(staged==null)return null;
            var candidate=reads.execute(s->prepare(reference,staged.archiveRef()));var prepared=authorize(Objects.requireNonNull(candidate));Instant raw=clock.instant();if(raw.isBefore(prepared.minimum()))throw invalid();Instant sealedAt=raw.truncatedTo(ChronoUnit.MICROS);String owner=reference.ownerId(),payload=payload(prepared,sealedAt);
            writes.execute(new SharedWriteExecutor.ActionContext(JdbcSourceValuePartialReceipts.action(owner),JdbcSourceValuePartialReceipts.receiptId(owner),prepared.proof().hash()),key->{var old=store.read(owner);return old==null?null:decode(old,prepared);},context->{
                store.lockOwner(owner);if(prepared.snapshot().fragments().isEmpty()){if(!prepared.snapshot().plan().chunks().isEmpty()||prepared.permit()==null)throw invalid();}else roots.lockOriginal(prepared.snapshot().fragments().stream().map(SourceStageResolutionService.Fragment::nativeRoot).toList());verifyPartial(prepared);var old=store.read(owner);if(old!=null)return decode(old,prepared);store.append(owner,payload,sealedAt);return decode(Objects.requireNonNull(store.read(owner)),prepared);
            });
            return reads.execute(s->Objects.requireNonNull(readCommitted(reference)));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(JdbcSourceStageRoots.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(RuntimeException rejected){throw invalid();}
    }
    public Committed readCommitted(FrozenSourceStagePlan.Reference reference){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);var nativeReceipt=store.read(reference.ownerId());if(nativeReceipt==null)return null;
            if(nativeReceipt.payloadBytes()==null||nativeReceipt.payloadBytes()<1||nativeReceipt.payloadBytes()>8192)throw invalid();var body=CanonicalJson.parse(nativeReceipt.payload());
            var archive=FrozenSourceValuePartial.Reference.fromJson(JdbcFrozenInputs.canonical(body.get("archive_ref")));return decode(nativeReceipt,prepare(reference,archive));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Prepared prepare(FrozenSourceStagePlan.Reference reference,FrozenSourceValuePartial.Reference archive){
        JdbcSourceDedupResults.requireReadOnly();var snapshot=resolutions.read(reference);if(!snapshot.settled())throw invalid();var proof=SourceValueStageResult.createResolved(snapshot);if(!"source-value-stage-result-v2".equals(CanonicalJson.parse(proof.json()).get("schema_version").asText()))throw invalid();archives.verifyStaged(archive,proof);Instant minimum=snapshot.plan().scope().cutoff();for(var fragment:snapshot.fragments())if(fragment.value()!=null&&fragment.value().appliedAt().isAfter(minimum))minimum=fragment.value().appliedAt();return new Prepared(snapshot,proof,archive,nativeHash(snapshot),minimum,null);
    }
    private Committed decode(JdbcSourceValuePartialReceipts.Raw raw,Prepared actual){
        String owner=actual.snapshot().reference().ownerId();if(raw.targetBytes()==null||raw.targetBytes()<1||raw.targetBytes()>2048||raw.payloadBytes()==null||raw.payloadBytes()<1||raw.payloadBytes()>8192)throw invalid();
        var target=CanonicalJson.parse(raw.target());var targetFields=Set.of("kind","id");WireValues.object(target,targetFields,targetFields);var body=CanonicalJson.parse(raw.payload());var fields=Set.of("schema_version","plan_ref","archive_ref","result_hash","native_capture_hash","sealed_at");WireValues.object(body,fields,fields);
        String payload=JdbcFrozenInputs.canonical(body);Instant sealed=WireValues.utcMicroseconds(body.get("sealed_at"));if(sealed.isBefore(actual.minimum()))throw invalid();
        if(!JdbcSourceValuePartialReceipts.receiptId(owner).equals(raw.id())||!"disposition".equals(raw.kind())||!JdbcSourceValuePartialReceipts.TYPE.equals(raw.targetType())||!JdbcSourceValuePartialReceipts.ACTOR.equals(raw.actor())||!JdbcSourceValuePartialReceipts.REASON.equals(raw.reason())
                ||!"generation_intent".equals(WireValues.text(target.get("kind")))||!owner.equals(WireValues.text(target.get("id")))||!Arrays.equals(raw.targetHash(),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target)))||!Arrays.equals(raw.payloadHash(),JdbcGenerationLineage.hash(payload))||!Arrays.equals(raw.actionHash(),JdbcGenerationLineage.hash(JdbcSourceValuePartialReceipts.action(owner)))||!sealed.equals(raw.actedAt())||!payload.equals(payload(actual,sealed)))throw invalid();
        return new Committed(actual.proof(),actual.archive(),payload,sealed.isAfter(actual.minimum())?sealed:actual.minimum());
    }
    private static String payload(Prepared original,Instant sealed){
        var body=JsonMapper.shared().createObjectNode().put("schema_version","source-value-partial-seal-v1").put("result_hash",original.proof().hash()).put("native_capture_hash",original.nativeHash()).put("sealed_at",time(sealed));body.set("plan_ref",CanonicalJson.parse(original.snapshot().reference().json()));body.set("archive_ref",CanonicalJson.parse(original.archive().json()));String raw=JdbcFrozenInputs.canonical(body);if(raw.getBytes(StandardCharsets.UTF_8).length>8192)throw invalid();return raw;
    }
    static String nativeHash(SourceStageResolutionService.Snapshot snapshot){
        Objects.requireNonNull(snapshot);
        if(!snapshot.fragments().isEmpty())return SourceDedupPartialSealService.nativeHash(snapshot);
        var body=JsonMapper.shared().createObjectNode().put("schema_version","source-stage-native-capture-v1");body.putArray("roots");
        return CanonicalJson.hash(CanonicalJson.Domain.ACTION,body);
    }
    private Prepared authorize(Prepared candidate){
        var scope=candidate.snapshot().plan().scope();var context=CanonicalJson.parse(SourceValueStageContext.readAny(scope.stageContextJson(),scope.businessDate(),scope.cutoff()));
        if(!"source-dedup-dependency-v2".equals(WireValues.text(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version"))))return candidate;
        if(partialPermits==null)throw invalid();var permit=partialPermits.capture(context.get(FrozenSourceDedupDependency.FIELD),scope.cutoff());
        if(permit==null||!permit.dependency().json().equals(JdbcFrozenInputs.canonical(context.get(FrozenSourceDedupDependency.FIELD))))throw invalid();
        return new Prepared(candidate.snapshot(),candidate.proof(),candidate.archive(),candidate.nativeHash(),candidate.minimum(),permit);
    }
    private void verifyPartial(Prepared prepared){
        var scope=prepared.snapshot().plan().scope();var context=CanonicalJson.parse(SourceValueStageContext.readAny(scope.stageContextJson(),scope.businessDate(),scope.cutoff()));
        if(!"source-dedup-dependency-v2".equals(WireValues.text(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version")))){if(prepared.permit()!=null)throw invalid();return;}
        if(partialPermits==null||prepared.permit()==null||!partialPermits.verify(prepared.permit(),scope.cutoff()).json().equals(JdbcFrozenInputs.canonical(context.get(FrozenSourceDedupDependency.FIELD))))throw invalid();
    }
    private static String time(Instant value){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
    private static IllegalStateException invalid(){return new IllegalStateException("original source value partial seal unavailable");}
}
