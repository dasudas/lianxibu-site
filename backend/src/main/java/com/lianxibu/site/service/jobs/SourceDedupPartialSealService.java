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

/** Atomically seals original partial P1 proofs. Observation, parent handoff and P2 are separate. */
public final class SourceDedupPartialSealService {
    public static final class Committed {
        private final SourceDedupStageResult proof;private final FrozenSourceDedupPartial.Reference archive;private final String receipt;private final Instant minimum;
        private Committed(SourceDedupStageResult proof,FrozenSourceDedupPartial.Reference archive,String receipt,Instant minimum){this.proof=proof;this.archive=archive;this.receipt=receipt;this.minimum=minimum;}
        public SourceDedupStageResult proof(){return proof;}public FrozenSourceDedupPartial.Reference archiveRef(){return archive;}public String receiptJson(){return receipt;}public Instant minimumObservationTime(){return minimum;}
        @Override public String toString(){return "CommittedSourceDedupPartial[redacted]";}
    }
    private record Prepared(SourceStageResolutionService.Snapshot snapshot,SourceDedupStageResult proof,FrozenSourceDedupPartial.Reference archive,String nativeHash,Instant minimum){ }
    private final SourceStageResolutionService resolutions;private final SourceDedupPartialArchiveService archives;private final JdbcSourceDedupPartialReceipts store;private final JdbcSourceStageRoots roots;
    private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;
    public SourceDedupPartialSealService(SourceStageResolutionService resolutions,SourceDedupPartialArchiveService archives,JdbcSourceDedupPartialReceipts store,JdbcSourceStageRoots roots,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
        this.resolutions=Objects.requireNonNull(resolutions);this.archives=Objects.requireNonNull(archives);this.store=Objects.requireNonNull(store);this.roots=Objects.requireNonNull(roots);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Committed seal(FrozenSourceStagePlan.Reference reference){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Objects.requireNonNull(reference);var previous=reads.execute(s->readCommitted(reference));if(previous!=null)return previous;
            var staged=archives.stage(reference);if(staged==null)return null;
            var prepared=reads.execute(s->prepare(reference,staged.archiveRef()));Objects.requireNonNull(prepared);Instant raw=clock.instant();if(raw.isBefore(prepared.minimum()))throw invalid();Instant sealedAt=raw.truncatedTo(ChronoUnit.MICROS);String owner=reference.ownerId(),payload=payload(prepared,sealedAt);
            writes.execute(new SharedWriteExecutor.ActionContext(JdbcSourceDedupPartialReceipts.action(owner),JdbcSourceDedupPartialReceipts.receiptId(owner),prepared.proof().hash()),key->{var old=store.read(owner);return old==null?null:decode(old,prepared);},context->{
                store.lockOwner(owner,prepared.snapshot().plan().stage().name().toLowerCase(Locale.ROOT));roots.lockOriginal(prepared.snapshot().fragments().stream().map(SourceStageResolutionService.Fragment::nativeRoot).toList());var old=store.read(owner);if(old!=null)return decode(old,prepared);store.append(owner,payload,sealedAt);return decode(Objects.requireNonNull(store.read(owner)),prepared);
            });
            return reads.execute(s->Objects.requireNonNull(readCommitted(reference)));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(JdbcSourceStageRoots.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(RuntimeException rejected){throw invalid();}
    }
    public Committed readCommitted(FrozenSourceStagePlan.Reference reference){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);var nativeReceipt=store.read(reference.ownerId());if(nativeReceipt==null)return null;
            if(nativeReceipt.payloadBytes()==null||nativeReceipt.payloadBytes()<1||nativeReceipt.payloadBytes()>8192)throw invalid();var body=CanonicalJson.parse(nativeReceipt.payload());
            var archive=FrozenSourceDedupPartial.Reference.fromJson(JdbcFrozenInputs.canonical(body.get("archive_ref")));return decode(nativeReceipt,prepare(reference,archive));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Prepared prepare(FrozenSourceStagePlan.Reference reference,FrozenSourceDedupPartial.Reference archive){
        JdbcSourceDedupResults.requireReadOnly();var snapshot=resolutions.read(reference);if(!snapshot.settled()||!snapshot.hasFailures())throw invalid();var proof=SourceDedupStageResult.createResolved(snapshot);archives.verifyStaged(archive,proof);Instant minimum=snapshot.plan().scope().cutoff();for(var fragment:snapshot.fragments())if(fragment.dedup()!=null&&fragment.dedup().appliedAt().isAfter(minimum))minimum=fragment.dedup().appliedAt();return new Prepared(snapshot,proof,archive,nativeHash(snapshot),minimum);
    }
    private Committed decode(JdbcSourceDedupPartialReceipts.Raw raw,Prepared actual){
        String owner=actual.snapshot().reference().ownerId();if(raw.targetBytes()==null||raw.targetBytes()<1||raw.targetBytes()>2048||raw.payloadBytes()==null||raw.payloadBytes()<1||raw.payloadBytes()>8192)throw invalid();
        var target=CanonicalJson.parse(raw.target());var targetFields=Set.of("kind","id");WireValues.object(target,targetFields,targetFields);var body=CanonicalJson.parse(raw.payload());var fields=Set.of("schema_version","plan_ref","archive_ref","result_hash","native_capture_hash","sealed_at");WireValues.object(body,fields,fields);
        String payload=JdbcFrozenInputs.canonical(body);Instant sealed=WireValues.utcMicroseconds(body.get("sealed_at"));if(sealed.isBefore(actual.minimum()))throw invalid();
        if(!JdbcSourceDedupPartialReceipts.receiptId(owner).equals(raw.id())||!"disposition".equals(raw.kind())||!JdbcSourceDedupPartialReceipts.TYPE.equals(raw.targetType())||!JdbcSourceDedupPartialReceipts.ACTOR.equals(raw.actor())||!JdbcSourceDedupPartialReceipts.REASON.equals(raw.reason())
                ||!"generation_intent".equals(WireValues.text(target.get("kind")))||!owner.equals(WireValues.text(target.get("id")))||!Arrays.equals(raw.targetHash(),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target)))||!Arrays.equals(raw.payloadHash(),JdbcGenerationLineage.hash(payload))||!Arrays.equals(raw.actionHash(),JdbcGenerationLineage.hash(JdbcSourceDedupPartialReceipts.action(owner)))||!sealed.equals(raw.actedAt())||!payload.equals(payload(actual,sealed)))throw invalid();
        return new Committed(actual.proof(),actual.archive(),payload,sealed.isAfter(actual.minimum())?sealed:actual.minimum());
    }
    private static String payload(Prepared original,Instant sealed){
        var body=JsonMapper.shared().createObjectNode().put("schema_version","source-dedup-partial-seal-v1").put("result_hash",original.proof().hash()).put("native_capture_hash",original.nativeHash()).put("sealed_at",time(sealed));body.set("plan_ref",CanonicalJson.parse(original.snapshot().reference().json()));body.set("archive_ref",CanonicalJson.parse(original.archive().json()));String raw=JdbcFrozenInputs.canonical(body);if(raw.getBytes(StandardCharsets.UTF_8).length>8192)throw invalid();return raw;
    }
    static String nativeHash(SourceStageResolutionService.Snapshot snapshot){
        if(snapshot.fragments().isEmpty())throw invalid();if(snapshot.fragments().size()>100)throw new GenerationFlowService.CapacityGuarded();var body=JsonMapper.shared().createObjectNode().put("schema_version","source-stage-native-capture-v1");var rows=body.putArray("roots");
        for(var fragment:snapshot.fragments()){
            var root=Objects.requireNonNull(fragment.nativeRoot());rows.addObject().put("id",root.id()).put("intent",root.intent()).put("key",root.key()).put("generation",root.generation()).put("part",root.part()).put("type",root.type()).put("day",root.day().toString())
                .put("state",root.state()).put("revision",root.revision()).put("calls",root.calls()).put("maximum",root.maximum()).put("inputState",root.inputState()).put("inputHash",root.inputHash()).put("cutoff",time(root.cutoff())).put("application",root.application()).put("resultHash",root.resultHash()).put("appliedAt",root.appliedAt()==null?null:time(root.appliedAt())).put("updatedAt",time(root.updatedAt()));
        }
        if(CanonicalJson.bytes(body).length>512*1024)throw new GenerationFlowService.CapacityGuarded();return CanonicalJson.hash(CanonicalJson.Domain.ACTION,body);
    }
    private static String time(Instant value){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
    private static IllegalStateException invalid(){return new IllegalStateException("original source dedup partial seal unavailable");}
}
