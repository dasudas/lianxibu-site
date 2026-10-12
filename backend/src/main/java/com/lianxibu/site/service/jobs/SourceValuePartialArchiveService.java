package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Stages original partial P2 bytes. A separate atomic seal must grant durable completion. */
public final class SourceValuePartialArchiveService {
    public record Staged(SourceStageResolutionService.Snapshot original,FrozenSourceValuePartial.Reference archiveRef){
        public Staged {Objects.requireNonNull(original);Objects.requireNonNull(archiveRef);}
        @Override public String toString(){return "StagedSourceValuePartial[redacted]";}
    }
    private final SourceStageResolutionService resolutions;private final JdbcSourceValuePartialBlocks store;private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;
    public SourceValuePartialArchiveService(SourceStageResolutionService resolutions,JdbcSourceValuePartialBlocks store,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
        this.resolutions=Objects.requireNonNull(resolutions);this.store=Objects.requireNonNull(store);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Staged stage(FrozenSourceStagePlan.Reference reference){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var snapshot=reads.execute(s->resolutions.read(reference));
            if(snapshot==null||!snapshot.settled())return null;
            var original=SourceValueStageResult.createResolved(snapshot);if(!"source-value-stage-result-v2".equals(CanonicalJson.parse(original.json()).get("schema_version").asText()))return null;var encoded=FrozenSourceValuePartial.encode(original);var ref=encoded.reference();String stamp=JdbcSourceValuePartialBlocks.stamp(ref.ownerId(),ref.indexHash());
            Instant minimum=snapshot.plan().scope().cutoff();for(var fragment:snapshot.fragments())if(fragment.value()!=null&&fragment.value().appliedAt().isAfter(minimum))minimum=fragment.value().appliedAt();
            Instant raw=clock.instant();if(raw.isBefore(minimum))throw invalid();Instant now=raw.truncatedTo(ChronoUnit.MICROS);
            for(int n=0;n<encoded.payloads().size();n++){
                int number=n;String payload=encoded.payloads().get(n);String id=JdbcSourceValuePartialBlocks.partId(ref.ownerId(),stamp,n);String action="source-value-partial-block:"+id;
                writes.execute(new SharedWriteExecutor.ActionContext(action,id,CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(payload))),key->{
                    var old=store.part(ref.ownerId(),stamp,number);if(old==null)return null;if(!payload.equals(old.payload()))throw invalid();return true;
                },context->{store.lockOwner(ref.ownerId());store.put(ref.ownerId(),stamp,number,payload,now);return true;});
            }
            reads.execute(s->{var current=SourceValueStageResult.createResolved(resolutions.read(reference));verifyStaged(ref,current);if(!current.json().equals(original.json()))throw invalid();return true;});
            return new Staged(snapshot,ref);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Caller supplies an actual original proof; blocks alone never establish a partial seal. */
    public SourceValueStageResult verifyStaged(FrozenSourceValuePartial.Reference ref,SourceValueStageResult original){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(ref);store.immutableOwner(ref.ownerId());String stamp=JdbcSourceValuePartialBlocks.stamp(ref.ownerId(),ref.indexHash());
            return FrozenSourceValuePartial.decode(ref,store.parts(ref.ownerId(),stamp).stream().map(JdbcSourceValuePartialBlocks.Part::payload).toList(),original);
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original partial source value staging unavailable");}
}
