package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.FrozenContentBody;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.time.Instant;
import java.util.*;

/** Pure report mapping. Callers must verify native completion, current validity and the entire candidate universe. */
public final class CatalogAnalysisReportCandidate {
    public record Value(Candidate candidate,Dependency dependency,String referenceJson){
        public Value{Objects.requireNonNull(candidate);Objects.requireNonNull(dependency);Objects.requireNonNull(referenceJson);}
        @Override public String toString(){return "CatalogAnalysisReportCandidate[redacted]";}
    }
    private CatalogAnalysisReportCandidate(){ }
    public static Value create(CatalogDayAnalysisCompletionService.Completed completed,Instant cutoff,boolean effectiveValid,boolean blocked){
        try{
            Objects.requireNonNull(completed);var reservation=completed.reservation();var version=completed.version();
            if(cutoff==null||cutoff.getNano()%1000!=0||completed.availableAt().getNano()%1000!=0||completed.availableAt().isAfter(cutoff)||completed.availableAt().isBefore(reservation.reservedAt()))throw invalid();
            var snapshot=CanonicalJson.parse(completed.snapshotJson());var base=CatalogAnalysisInputProfile.complete(snapshot);var context=base.command().applicationContext();
            String root=JdbcCatalogAnalysisPreparedInputs.root(reservation.intentId());
            if(!completed.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot))||!reservation.json().equals(JdbcFrozenInputs.canonical(snapshot.get(CatalogAnalysisInputProfile.FIELD).get("reservation")))
                ||!version.inputJobId().equals(root)||!version.contentKind().equals(reservation.category())||!version.contentId().equals(context.contentId())||context.generation()!=reservation.generation()
                ||!reservation.entityId().equals(context.input(root,"candidate-check",completed.inputHash(),0).targetEntityId())||!Set.of("new_entity","version_recommended").contains(reservation.trigger()))throw invalid();
            var body=CanonicalJson.parse(completed.bodyJson());FrozenContentBody.read(version.contentKind(),body);
            if(!version.bodyHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body))||!reservation.entityId().equals(WireValues.id(body.get("entity_id"),false)))throw invalid();
            var allowed=context.input(root,"candidate-check",completed.inputHash(),0).allowedCitationIds();
            for(var citation:body.get("citation_ids"))if(!allowed.contains(WireValues.id(citation,false)))throw invalid();
            String reference=JdbcFrozenContentVersions.version(version),id=JdbcFrozenInputs.stableId("report-catalog-version",reference);
            var metadata=new Metadata(new ResourceRef("content_version",version.contentId(),null),version.revision(),id,version.bodyHash(),null,null,null,reservation.day(),WireValues.text(body.get("title")),null,null);
            var evidence=new Evidence(id,"analysis",reservation.day(),completed.availableAt(),version.bodyHash(),JdbcFrozenInputs.canonical(body),effectiveValid,blocked,"accepted",List.of(root));
            var candidate=new Candidate(Group.NEW_CATALOG_ANALYSIS,metadata,evidence,reservation.category(),false,true,null,null);
            var dependency=new Dependency(root,base.command().stage(),"succeeded",completed.availableAt());
            return new Value(candidate,dependency,reference);
        }catch(GenerationFlowService.CapacityGuarded capacity){throw capacity;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog report candidate unavailable");}
}
