package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Commits a complete original P1 handoff, source reference and fresh P2 intent together. */
public final class SourceValueIntentService {
    public record Header(String runId,String sourceId,String sourceIdentity,String category,String kind,LocalDate day,long generation,String manifestHash,long expected,long accepted,long excluded,long pending,String activation,String delivery,long revision,List<String> refs){
        public Header {refs=List.copyOf(refs);}
        @Override public String toString(){return "OriginalSourceHandoffHeader[redacted]";}
    }
    public interface SourceLinks {
        Header read(String runId);
        Header locked(String runId);
        long link(String runId,String p1Owner,String p2Owner);
    }
    private static final Set<String> PENDING=Set.of("waiting_input","prepared","queued","running");
    private static final Set<String> RUNS=Set.of("daily","historical_backfill","production_qualification","correction");
    private static final Set<String> DELIVERY=Set.of("complete","partial","no_usable_input","empty_success");
    private record Snapshot(SourceDedupStageProofService.Committed committed,SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe){ }
    private final SourceDedupStageProofService proofs;private final SourceDedupStageAvailabilityService observer;private final FrozenSourceStagePlanService plans;private final FrozenSourceUniverseService universes;
    private final SourceValueIntentReceiptService metadata;private final CollectionHandoffProofService collections;private final JdbcSourceValueIntents store;private final SourceLinks links;
    private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;private final BooleanSupplier allowed;private final SourceDedupPartialDependencyPermitService partialPermits;
    public SourceValueIntentService(SourceDedupStageProofService proofs,SourceDedupStageAvailabilityService observer,FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,SourceValueIntentReceiptService metadata,CollectionHandoffProofService collections,JdbcSourceValueIntents store,SourceLinks links,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock,BooleanSupplier allowed){
        this(proofs,observer,plans,universes,metadata,collections,store,links,writes,tx,clock,allowed,null);
    }
    public SourceValueIntentService(SourceDedupStageProofService proofs,SourceDedupStageAvailabilityService observer,FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,SourceValueIntentReceiptService metadata,CollectionHandoffProofService collections,JdbcSourceValueIntents store,SourceLinks links,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock,BooleanSupplier allowed,SourceDedupPartialDependencyPermitService partialPermits){
        this.partialPermits=partialPermits;
        this.proofs=Objects.requireNonNull(proofs);this.observer=Objects.requireNonNull(observer);this.plans=Objects.requireNonNull(plans);this.universes=Objects.requireNonNull(universes);this.metadata=Objects.requireNonNull(metadata);this.collections=Objects.requireNonNull(collections);this.store=Objects.requireNonNull(store);this.links=Objects.requireNonNull(links);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public SourceValueIntentReceiptService.Handoff handoff(FrozenSourceStagePlan.Reference reference){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Objects.requireNonNull(reference);
            var initial=reads.execute(s->snapshot(reference));if(initial==null)return null;var previous=reads.execute(s->lookup(initial));if(previous!=null)return previous;
            if(!allowed.getAsBoolean())throw invalid();Instant observed=observer.capture(reference);if(observed==null)return null;
            var original=reads.execute(s->{var value=snapshot(reference);if(value==null||!observed.equals(observer.availableAt(value.committed().proof(),observed)))throw invalid();return value;});Objects.requireNonNull(original);
            Instant raw=clock.instant();if(raw.isBefore(observed)||raw.isBefore(original.committed().minimumObservationTime()))throw invalid();Instant now=raw.truncatedTo(ChronoUnit.MICROS);
            var permit=capturePartial(original,observed,now);
            return writes.execute(new SharedWriteExecutor.ActionContext(JdbcSourceValueIntents.action(reference.ownerId()),reference.ownerId(),original.committed().proof().hash()),key->lookup(original),context->{
                if(!allowed.getAsBoolean())throw invalid();var cursor=store.lockP1(reference.ownerId());verifyPartial(permit,now);var old=metadata.read(reference.ownerId());if(old!=null)verify(old,original);
                String terminal=terminal(original);if(!PENDING.contains(cursor.state())&&!(old!=null&&terminal.equals(cursor.state())))throw invalid();
                verifyCollection(collections.read(original.universe().runId()),original);verifyHeader(links.locked(original.universe().runId()),original);
                var value=old;
                if(value==null){var candidate=SourceValueIntentReceiptService.Handoff.create(original.committed().proof(),original.universe(),observed,1,now);long generation=metadata.nextGeneration(candidate.p2Key(),candidate.day());value=metadata.reserve(SourceValueIntentReceiptService.Handoff.create(original.committed().proof(),original.universe(),observed,generation,now));}
                links.link(value.runId(),reference.ownerId(),value.p2Owner());if(!terminal.equals(cursor.state()))store.completeP1(cursor,value.stageParts()==0,now);
                var completed=lookup(original);if(completed==null||!completed.json().equals(value.json()))throw invalid();return completed;
            });
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public SourceValueIntentReceiptService.Handoff readCommitted(FrozenSourceStagePlan.Reference reference){
        try {JdbcSourceDedupResults.requireReadOnly();var original=snapshot(Objects.requireNonNull(reference));return original==null?null:lookup(original);}
        catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Snapshot snapshot(FrozenSourceStagePlan.Reference reference){
        JdbcSourceDedupResults.requireReadOnly();var committed=proofs.readCommitted(reference);if(committed==null)return null;var plan=plans.readImmutable(reference);var universe=universes.readImmutable(plan.reference());return new Snapshot(committed,plan,universe);
    }
    private SourceDedupPartialDependencyPermitService.Captured capturePartial(Snapshot original,Instant observed,Instant cutoff){
        if(!"source-dedup-stage-result-v2".equals(CanonicalJson.parse(original.committed().proof().json()).path("schema_version").asText()))return null;
        if(partialPermits==null)throw invalid();var profile=FrozenSourceDedupDependency.create(original.committed().proof(),observed,cutoff);var permit=partialPermits.capture(profile.toJson(),cutoff);if(permit==null||!profile.json().equals(permit.dependency().json()))throw invalid();return permit;
    }
    private void verifyPartial(SourceDedupPartialDependencyPermitService.Captured permit,Instant cutoff){if(permit!=null){if(partialPermits==null||!partialPermits.verify(permit,cutoff).json().equals(permit.dependency().json()))throw invalid();}}
    // The immutable proof was validated immediately before entering the short writer. No full decoder runs here.
    private SourceValueIntentReceiptService.Handoff lookup(Snapshot original){
        String owner=original.committed().proof().planRef().ownerId();var nativeOwner=store.owner(owner);var header=links.read(original.universe().runId());verifyHeader(header,original);
        if(!PENDING.contains(nativeOwner.state())&&!terminal(original).equals(nativeOwner.state()))throw invalid();var value=metadata.read(owner);
        if(value==null){if(terminal(original).equals(nativeOwner.state()))throw invalid();return null;}verify(value,original);
        return terminal(original).equals(nativeOwner.state())&&header.refs().contains(value.p2Owner())?value:null;
    }
    private static void verify(SourceValueIntentReceiptService.Handoff value,Snapshot original){
        if(value.observedAt().isBefore(original.committed().minimumObservationTime()))throw invalid();var expected=SourceValueIntentReceiptService.Handoff.create(original.committed().proof(),original.universe(),value.observedAt(),value.p2Generation(),value.createdAt());if(!expected.json().equals(value.json()))throw invalid();
    }
    private static String terminal(Snapshot original){return original.plan().chunks().isEmpty()?"no_input":"succeeded";}
    private static void verifyHeader(Header h,Snapshot original){
        var u=original.universe();var p=original.plan();if(!h.runId().equals(u.runId())||!h.sourceId().equals(u.sourceId())||!h.sourceIdentity().equals(u.sourceId())||!"news_tech".equals(h.category())||!RUNS.contains(h.kind())||!"production".equals(h.activation())||!DELIVERY.contains(h.delivery())||!h.day().equals(p.scope().businessDate())||h.generation()!=p.generation()||!h.manifestHash().equals(u.manifestHash())||h.expected()!=u.members().size()||h.accepted()!=u.accepted()||h.excluded()!=u.excluded()||h.pending()!=0||!h.refs().contains(original.committed().proof().planRef().ownerId()))throw invalid();
    }
    private static void verifyCollection(CollectionHandoffProofService.Proof proof,Snapshot original){
        var f=proof.facts();var u=original.universe();var p=original.plan();if(!proof.intentId().equals(original.committed().proof().planRef().ownerId())||!CollectionHandoffProofService.logicalKey(f).equals(p.logicalKey())||!f.runId().equals(u.runId())||!f.sourceId().equals(u.sourceId())||!"news_tech".equals(f.category())||!"production".equals(f.activationStage())||!RUNS.contains(f.runKind())||!f.businessDate().equals(p.scope().businessDate())||f.generation()!=p.generation()||!f.manifestHash().equals(u.manifestHash())||f.expected()!=u.members().size()||f.accepted()!=u.accepted()||f.excluded()!=u.excluded())throw invalid();
    }
    private static IllegalStateException invalid(){return new IllegalStateException("complete original source value intent handoff unavailable");}
}
