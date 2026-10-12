package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Stages original partial P1 bytes. A separate atomic seal must grant durable completion. */
public final class SourceDedupPartialArchiveService {
    public record Staged(SourceStageResolutionService.Snapshot original,FrozenSourceDedupPartial.Reference archiveRef){
        public Staged {Objects.requireNonNull(original);Objects.requireNonNull(archiveRef);}
        @Override public String toString(){return "StagedSourceDedupPartial[redacted]";}
    }
    private final SourceStageResolutionService resolutions;private final JdbcSourceDedupPartialBlocks store;private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;
    public SourceDedupPartialArchiveService(SourceStageResolutionService resolutions,JdbcSourceDedupPartialBlocks store,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
        this.resolutions=Objects.requireNonNull(resolutions);this.store=Objects.requireNonNull(store);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Staged stage(FrozenSourceStagePlan.Reference reference){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var snapshot=reads.execute(s->resolutions.read(reference));
            if(snapshot==null||!snapshot.settled()||!snapshot.hasFailures())return null;
            var original=SourceDedupStageResult.createResolved(snapshot);var encoded=FrozenSourceDedupPartial.encode(original);var ref=encoded.reference();String stamp=JdbcSourceDedupPartialBlocks.stamp(ref.ownerId(),ref.indexHash());
            Instant minimum=snapshot.plan().scope().cutoff();for(var fragment:snapshot.fragments())if(fragment.dedup()!=null&&fragment.dedup().appliedAt().isAfter(minimum))minimum=fragment.dedup().appliedAt();
            Instant raw=clock.instant();if(raw.isBefore(minimum))throw invalid();Instant now=raw.truncatedTo(ChronoUnit.MICROS);
            for(int n=0;n<encoded.payloads().size();n++){
                int number=n;String payload=encoded.payloads().get(n);String id=JdbcSourceDedupPartialBlocks.partId(ref.ownerId(),stamp,n);String action="source-dedup-partial-block:"+id;
                writes.execute(new SharedWriteExecutor.ActionContext(action,id,CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(payload))),key->{
                    var old=store.part(ref.ownerId(),stamp,number);if(old==null)return null;if(!payload.equals(old.payload()))throw invalid();return true;
                },context->{store.lockOwner(ref.ownerId(),snapshot.plan().stage().name().toLowerCase(java.util.Locale.ROOT));store.put(ref.ownerId(),stamp,number,payload,now);return true;});
            }
            reads.execute(s->{var current=SourceDedupStageResult.createResolved(resolutions.read(reference));verifyStaged(ref,current);if(!current.json().equals(original.json()))throw invalid();return true;});
            return new Staged(snapshot,ref);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Caller supplies an actual original proof; blocks alone never establish a partial seal. */
    public SourceDedupStageResult verifyStaged(FrozenSourceDedupPartial.Reference ref,SourceDedupStageResult original){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(ref);store.immutableOwner(ref.ownerId(),WireValues.text(CanonicalJson.parse(original.observationKeyJson()).get("stage")));String stamp=JdbcSourceDedupPartialBlocks.stamp(ref.ownerId(),ref.indexHash());
            return FrozenSourceDedupPartial.decode(ref,store.parts(ref.ownerId(),stamp).stream().map(JdbcSourceDedupPartialBlocks.Part::payload).toList(),original);
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original partial source dedup staging unavailable");}
}
