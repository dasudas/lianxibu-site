package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Stages each original output block in a separate short transaction, without declaring final success. */
public final class CatalogSourceFinalArchiveService {
    public record Staged(CatalogSourceFinalProofService.Committed original,FrozenCatalogSourceFinal.Reference archiveRef){
        public Staged {Objects.requireNonNull(original);Objects.requireNonNull(archiveRef);}
        @Override public String toString(){return "StagedCatalogSourceFinal[redacted]";}
    }
    private final CatalogSourceFinalProofService proofs;private final JdbcCatalogSourceFinalBlocks store;private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;
    public CatalogSourceFinalArchiveService(CatalogSourceFinalProofService proofs,JdbcCatalogSourceFinalBlocks store,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
        this.proofs=Objects.requireNonNull(proofs);this.store=Objects.requireNonNull(store);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Staged stage(FrozenSourceStagePlan.Reference reference){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var proof=reads.execute(s->proofs.readCommitted(reference));if(proof==null)return null;
            var encoded=FrozenCatalogSourceFinal.encode(proof.result());var ref=encoded.reference();String stamp=JdbcCatalogSourceFinalBlocks.stamp(ref.ownerId(),ref.indexHash());Instant raw=clock.instant();if(raw.isBefore(proof.minimumObservationTime()))throw invalid();Instant now=raw.truncatedTo(ChronoUnit.MICROS);
            for(int n=0;n<encoded.payloads().size();n++){
                int number=n;String payload=encoded.payloads().get(n);String id=JdbcCatalogSourceFinalBlocks.partId(ref.ownerId(),stamp,n);String action="catalog-source-final-block:"+id;
                writes.execute(new SharedWriteExecutor.ActionContext(action,id,CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(payload))),key->{
                    var old=store.part(ref.ownerId(),stamp,number);if(old==null)return null;if(!payload.equals(old.payload()))throw invalid();return true;
                },context->{store.lockOwner(ref.ownerId());store.put(ref.ownerId(),stamp,number,payload,now);return true;});
            }
            reads.execute(s->verifyStaged(ref,proof.result()));return new Staged(proof,ref);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Complete staged bytes still require a later final receipt and committed observation. */
    public CatalogSourceFinalResult verifyStaged(FrozenCatalogSourceFinal.Reference ref,CatalogSourceFinalResult original){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(ref);store.immutableOwner(ref.ownerId());String stamp=JdbcCatalogSourceFinalBlocks.stamp(ref.ownerId(),ref.indexHash());
            return FrozenCatalogSourceFinal.decode(ref,store.parts(ref.ownerId(),stamp).stream().map(JdbcCatalogSourceFinalBlocks.Part::payload).toList(),original);
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original final catalog source staging unavailable");}
}
