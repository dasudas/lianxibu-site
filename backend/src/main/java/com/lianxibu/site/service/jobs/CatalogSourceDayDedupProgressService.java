package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Original registered catalog day progress requires the full final proof and its first observation. */
public final class CatalogSourceDayDedupProgressService {
    private final SourceDayRunRegistration.Api runs;private final FrozenSourceStagePlanService plans;
    private final CatalogSourceFinalizationService finals;private final CatalogSourceFinalAvailabilityService availability;
    private final TransactionTemplate reads;private final String category;
    public CatalogSourceDayDedupProgressService(SourceDayRunRegistration.Api runs,FrozenSourceStagePlanService plans,
            CatalogSourceFinalizationService finals,CatalogSourceFinalAvailabilityService availability,PlatformTransactionManager manager,String category){
        this.runs=Objects.requireNonNull(runs);this.plans=Objects.requireNonNull(plans);this.finals=Objects.requireNonNull(finals);this.availability=Objects.requireNonNull(availability);
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public SourceDedupBarrier.Progress read(SourceDayRunRegistration.Request request,Instant sealedAt,Instant observedAt){
        if(TransactionSynchronizationManager.isActualTransactionActive()||request==null||!category.equals(request.source().category())
                ||sealedAt==null||sealedAt.getNano()%1000!=0||sealedAt.isBefore(request.registeredAt())||!sealedAt.isBefore(SourceDedupBarrier.deadline(request.day()))
                ||observedAt==null||observedAt.getNano()%1000!=0||observedAt.isBefore(sealedAt))throw invalid();
        var reservation=runs.lookup(request);if(reservation==null)return null;
        if(!request.equals(reservation.request())||!request.hash().equals(reservation.requestHash())||reservation.generation()!=1||reservation.reservedAt().isBefore(sealedAt))throw invalid();
        var address=CollectionIntentIdentity.create(request.source().id(),"daily",request.day(),1,category);
        var stage=GenerationFlowService.Stage.valueOf(category.toUpperCase(Locale.ROOT)+"_DEDUP");
        return reads.execute(status->{
            var reference=plans.findImmutableReference(address.id(),address.logicalKey(),1,stage,request.day());if(reference==null)return null;
            var committed=finals.readCommitted(reference);if(committed==null)return null;var receipt=committed.receipt();var body=CanonicalJson.parse(committed.result().json());
            if(!category.equals(receipt.category())||!request.runId().equals(receipt.runId())||!request.source().id().equals(receipt.sourceId())
                    ||!request.day().equals(receipt.businessDate())||receipt.collectionGeneration()!=1||receipt.position().generation()!=1
                    ||!address.id().equals(receipt.position().ownerId())||!address.logicalKey().equals(receipt.position().logicalKey())
                    ||!category.equals(WireValues.text(body.get("category")))||!request.runId().equals(WireValues.text(body.get("run_id")))
                    ||!request.source().id().equals(WireValues.text(body.get("source_id")))||!request.day().equals(WireValues.businessDate(body.get("business_date")))
                    ||WireValues.nonnegativeInteger(body.get("generation"))!=1||!address.logicalKey().equals(WireValues.text(body.get("logical_key")))
                    ||!reference.json().equals(com.lianxibu.site.repository.jobs.JdbcFrozenInputs.canonical(body.get("plan_ref"))))throw invalid();
            var at=availability.availableAt(committed,observedAt);if(at==null)return null;if(at.isBefore(reservation.reservedAt()))throw invalid();
            String coverage=committed.result().coverageState();SourceDedupBarrier.State state;
            if("partial".equals(coverage))state=SourceDedupBarrier.State.PARTIAL;
            else if("complete".equals(coverage))state=WireValues.nonnegativeInteger(body.get("eligible_count"))==0?SourceDedupBarrier.State.EMPTY_SUCCESS:SourceDedupBarrier.State.COMPLETE;
            else throw invalid();
            return new SourceDedupBarrier.Progress(category,request.day(),request.source().id(),request.source().configRevision(),request.source().effectiveConfigHash(),request.runId(),1,state,at,committed.result().hash());
        });
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog day dedup progress unavailable");}
}
