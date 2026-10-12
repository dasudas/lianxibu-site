package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Pure count and original body mapping. Native universe, source and reference qualification belong to the caller. */
public final class EntityHeatReportCandidate {
    public record Value(Candidate candidate,GenerationFlowService.Dependency dependency,String referenceJson) {
        @Override public String toString(){return "EntityHeatReportCandidate[redacted]";}
    }
    private EntityHeatReportCandidate(){ }
    public static Value create(EntityHeatCutoffProofService.Count count,NewsSourceReportComponentService.EntityLeaf leaf,Instant cutoff,boolean currentAssociationMatches) {
        return map(count,leaf,cutoff,currentAssociationMatches,false);
    }
    /** Pure explicit-cutoff mapping; caller must provide actual original count and source authority. */
    public static Value createSupplement(EntityHeatCutoffProofService.Count count,NewsSourceReportComponentService.EntityLeaf leaf,Instant cutoff,boolean currentAssociationMatches){return map(count,leaf,cutoff,currentAssociationMatches,true);}
    private static Value map(EntityHeatCutoffProofService.Count count,NewsSourceReportComponentService.EntityLeaf leaf,Instant cutoff,boolean currentAssociationMatches,boolean supplement) {
        try {
            Objects.requireNonNull(count);Objects.requireNonNull(leaf);var snapshot=count.snapshot();
            if(cutoff==null||cutoff.getNano()%1000!=0||!supplement&&!cutoff.atZone(ZoneId.of("Asia/Shanghai")).toLocalTime().equals(LocalTime.of(5,0))
                ||snapshot==null||!"news_tech".equals(snapshot.category())||snapshot.legacy()||snapshot.occurrenceCount()==null||snapshot.firstRecordedAt()==null||snapshot.firstRecordedAt().isAfter(cutoff)
                ||!snapshot.entityId().equals(leaf.entityId())||count.availableAt()==null||count.availableAt().getNano()%1000!=0||count.availableAt().isAfter(cutoff)
                ||count.eventId()==null||!count.eventId().matches("[0-9a-f]{64}")||count.eventHash()==null||!count.eventHash().matches("[0-9a-f]{64}")
                ||leaf.availableAt()==null||leaf.availableAt().getNano()%1000!=0||leaf.availableAt().isAfter(cutoff)||leaf.day()==null||!leaf.day().isBefore(cutoff.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())
                ||leaf.inputRevision()<0||leaf.validityRevision()<0||leaf.bodyHash()==null||!leaf.bodyHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(leaf.bodyJson()))))throw invalid();
            WireValues.id(leaf.itemId(),false);WireValues.id(leaf.runId(),false);WireValues.id(leaf.sourceId(),true);WireValues.id(leaf.rootId(),false);if(leaf.inputHash()==null||!leaf.inputHash().matches("[0-9a-f]{64}"))throw invalid();
            var reference=CanonicalJson.parse(leaf.referenceJson());WireValues.object(reference,Set.of("kind","id"),Set.of("kind","id"));
            if(!"article_revision".equals(WireValues.text(reference.get("kind"))))throw invalid();String revisionId=WireValues.id(reference.get("id"),false);
            var body=JsonMapper.shared().createObjectNode().put("schema_version","entity-heat-report-input-v1");body.set("entity_heat_snapshot",snapshot.toJson());
            body.putObject("count_event").put("id",count.eventId()).put("event_hash",count.eventHash()).put("available_at",count.availableAt().toString());
            var original=body.putObject("source_member").put("item_id",leaf.itemId()).put("source_id",leaf.sourceId()).put("run_id",leaf.runId()).put("source_item_id",leaf.sourceItemId()).put("business_date",leaf.day().toString()).put("root_id",leaf.rootId()).put("input_hash",leaf.inputHash()).put("input_revision",leaf.inputRevision()).put("body_hash",leaf.bodyHash()).put("validity_revision",leaf.validityRevision());
            original.set("reference",reference);original.set("body",CanonicalJson.parse(leaf.bodyJson()));String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body);
            String id=JdbcFrozenInputs.stableId("report-entity-heat",snapshot.entityId()+":"+count.eventId()+":"+count.eventHash()+":"+hash);
            var metadata=new Metadata(new ResourceRef("entity",snapshot.entityId(),null),snapshot.revision(),revisionId,hash,leaf.sourceId(),leaf.runId(),leaf.sourceItemId(),leaf.day(),leaf.title(),leaf.summary(),snapshot.occurrenceCount());
            Instant available=count.availableAt().isAfter(leaf.availableAt())?count.availableAt():leaf.availableAt();
            var evidence=new GenerationFlowService.Evidence(id,"entity_heat",leaf.day(),available,hash,JdbcFrozenInputs.canonical(body),leaf.effectiveValid(),leaf.blocked()||!currentAssociationMatches,"accepted",List.of(leaf.rootId()));
            var candidate=new Candidate(Group.HISTORICAL_HEAT,metadata,evidence,"news_tech",false,false,cutoff,currentAssociationMatches?null:"entity_association_changed");
            return new Value(candidate,new GenerationFlowService.Dependency(leaf.rootId(),GenerationFlowService.Stage.NEWS_VALUE,"succeeded",leaf.availableAt()),JdbcFrozenInputs.canonical(reference));
        } catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("qualified original entity heat candidate unavailable");}
}
