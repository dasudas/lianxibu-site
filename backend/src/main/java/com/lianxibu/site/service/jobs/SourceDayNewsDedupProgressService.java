package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Original registered daily P1 progress. Absence never means a successful empty result. */
public final class SourceDayNewsDedupProgressService {
    private final SourceDayRunRegistration.Api runs;
    private final FrozenSourceStagePlanService plans;
    private final SourceDedupStageProofService proofs;
    private final SourceDedupStageAvailabilityService availability;
    private final TransactionTemplate reads;
    public SourceDayNewsDedupProgressService(SourceDayRunRegistration.Api runs,FrozenSourceStagePlanService plans,
            SourceDedupStageProofService proofs,SourceDedupStageAvailabilityService availability,PlatformTransactionManager manager){
        this.runs=Objects.requireNonNull(runs);this.plans=Objects.requireNonNull(plans);this.proofs=Objects.requireNonNull(proofs);
        this.availability=Objects.requireNonNull(availability);reads=new TransactionTemplate(Objects.requireNonNull(manager));
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public SourceDedupBarrier.Progress read(SourceDayRunRegistration.Request request,Instant sealedAt,Instant observedAt){
        if(TransactionSynchronizationManager.isActualTransactionActive()||request==null||!"news_tech".equals(request.source().category())
                ||sealedAt==null||sealedAt.getNano()%1000!=0||sealedAt.isBefore(request.registeredAt())
                ||!sealedAt.isBefore(SourceDedupBarrier.deadline(request.day()))||observedAt==null||observedAt.getNano()%1000!=0
                ||observedAt.isBefore(sealedAt))throw invalid();
        var reservation=runs.lookup(request);if(reservation==null)return null;
        if(!request.equals(reservation.request())||!request.hash().equals(reservation.requestHash())
                ||reservation.generation()!=1||reservation.reservedAt().isBefore(sealedAt))throw invalid();
        var address=CollectionIntentIdentity.create(request.source().id(),"daily",request.day(),1,"news_tech");
        return reads.execute(status->{
            var reference=plans.findImmutableReference(address.id(),address.logicalKey(),1,GenerationFlowService.Stage.NEWS_DEDUP,request.day());
            if(reference==null)return null;
            var committed=proofs.readCommitted(reference);if(committed==null)return null;var proof=committed.proof();var body=CanonicalJson.parse(proof.json());
            if(!request.runId().equals(WireValues.text(body.get("run_id")))||!request.source().id().equals(WireValues.text(body.get("source_id")))
                    ||!request.day().equals(WireValues.businessDate(body.get("business_date")))||WireValues.nonnegativeInteger(body.get("generation"))!=1
                    ||!address.logicalKey().equals(WireValues.text(body.get("logical_key")))||!reference.equals(proof.planRef()))throw invalid();
            var at=availability.availableAt(proof,observedAt);if(at==null)return null;
            if(at.isBefore(reservation.reservedAt()))throw invalid();
            String schema=WireValues.text(body.get("schema_version"));SourceDedupBarrier.State state;
            if("source-dedup-stage-result-v2".equals(schema)){
                if(!"partial".equals(WireValues.text(body.get("coverage_state"))))throw invalid();state=SourceDedupBarrier.State.PARTIAL;
            }else if("source-dedup-stage-result-v1".equals(schema)){
                state=WireValues.nonnegativeInteger(body.get("eligible_count"))==0?SourceDedupBarrier.State.EMPTY_SUCCESS:SourceDedupBarrier.State.COMPLETE;
            }else throw invalid();
            return new SourceDedupBarrier.Progress("news_tech",request.day(),request.source().id(),request.source().configRevision(),
                    request.source().effectiveConfigHash(),request.runId(),1,state,at,proof.hash());
        });
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original news day dedup progress unavailable");}
}
