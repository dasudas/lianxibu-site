package com.lianxibu.site.service.content;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.ContentVersionKey;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.content.ContentService.*;
import com.lianxibu.site.service.jobs.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Public DTOs from exact retained proofs, called inside the facade's qualified read transaction. */
public final class FrozenPublishedProjection implements PublishedContentFacade.Projection {
    private final FrozenContentVersionService versions;
    private final ContentValiditySummaryService validity;
    private final GenerationLineageService lineage;
    public FrozenPublishedProjection(FrozenContentVersionService versions,ContentValiditySummaryService validity,GenerationLineageService lineage){this.versions=Objects.requireNonNull(versions);this.validity=Objects.requireNonNull(validity);this.lineage=Objects.requireNonNull(lineage);}
    @Override public ContentView project(PublishedContentFacade.Version version) {
        return project(version,"published",true,true);
    }
    /** Only the owner read service exposes this path, after authorization and a consistent read. */
    ContentView projectOwner(PublishedContentFacade.Version version,String publicationState,boolean reviewMatches) {
        if(!Set.of("draft","approved","published","withdrawn").contains(publicationState))throw new IllegalArgumentException("invalid content state");
        return project(version,publicationState,reviewMatches,false);
    }
    private ContentView project(PublishedContentFacade.Version version,String publicationState,boolean reviewMatches,boolean publicRead) {
        var key=new ContentVersionKey(version.kind(),version.id(),version.bodyRevision(),version.bodyHash(),version.inputOwner());
        var proof=versions.find(key).orElseThrow(FrozenPublishedProjection::unavailable);
        if(!key.equals(proof.version()))throw unavailable();
        var bodyNode=CanonicalJson.parse(proof.bodyJson());var snapshot=CanonicalJson.parse(proof.snapshotJson());var context=proof.context();
        if(!key.bodyHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,bodyNode))||!Arrays.equals(CanonicalJson.bytes(bodyNode),CanonicalJson.bytes(CanonicalJson.parse(version.bodyJson())))
            ||!version.title().equals(WireValues.text(bodyNode.get("title")))||!proof.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot))
            ||!context.json().equals(FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD)).json())
            ||!key.contentKind().equals(context.contentKind())||!key.contentId().equals(context.contentId())||!"1".equals(WireValues.text(snapshot.get("schema_version")))
            ||context.generation()!=WireValues.nonnegativeInteger(snapshot.get("generation"))||!context.stage().name().toLowerCase(Locale.ROOT).equals(WireValues.text(snapshot.get("stage"))))throw unavailable();
        var body=FrozenContentBody.read(version.kind(),bodyNode);var metadata=FrozenPublicMetadata.read(snapshot.get(FrozenPublicMetadata.FIELD),context);
        var relation=lineage.read(version.inputOwner());var state=validity.read(key);
        if(relation==null||state==null||!version.inputOwner().equals(relation.rootId())||relation.generation()!=context.generation()||publicRead&&!state.effectiveValid())throw unavailable();
        Instant cutoff=Instant.parse(WireValues.text(snapshot.get("cutoff")));if(cutoff.getNano()%1000!=0)throw unavailable();
        Coverage coverage;
        if(body instanceof ReportBody report) {
            ReportInputProfile.validated(snapshot);var profile=snapshot.get(ReportInputProfile.FIELD);
            if(!report.type().equals(WireValues.text(profile.get("report_type")))||!report.periodStart().equals(WireValues.utcMicroseconds(profile.get("period_start")))||!report.periodEnd().equals(WireValues.utcMicroseconds(profile.get("period_end")))
                ||!report.periodStart().equals(Instant.parse(context.periodStart()))||!report.periodEnd().equals(Instant.parse(context.periodEnd())))throw unavailable();
            coverage=coverage(profile.get("coverage"));
        } else {
            if(!context.stage().analysis())throw unavailable();
            var interpretation=(InterpretationBody)body;
            if(!interpretation.entityId().equals(context.input(version.inputOwner(),"public-projection",proof.inputHash(),0).targetEntityId()))throw unavailable();
            String coverageState=WireValues.text(snapshot.get("coverage_state"));var missing=snapshot.get("missing");
            if(!Set.of("complete","partial").contains(coverageState)||missing==null||!missing.isArray()||missing.size()>1000||"complete".equals(coverageState)!=missing.isEmpty())throw unavailable();
            List<MissingInput> omissions=new ArrayList<>();Set<String> seen=new HashSet<>();for(var id:missing){String value=WireValues.id(id,false);if(!seen.add(value))throw unavailable();omissions.add(new MissingInput("dependency",value,null,"missing_at_cutoff"));}
            coverage=new Coverage(coverageState,List.copyOf(omissions),List.of(),List.of());
        }
        LinkedHashSet<String> used=new LinkedHashSet<>();bodyNode.get("citation_ids").forEach(id->used.add(WireValues.id(id,false)));for(var section:bodyNode.get("sections"))section.get("citation_ids").forEach(id->used.add(WireValues.id(id,false)));
        Map<String,Citation> available=new HashMap<>();metadata.citations().forEach(c->available.put(c.id(),c));List<Citation> citations=new ArrayList<>();for(String id:used){var citation=available.get(id);if(citation==null)throw unavailable();citations.add(citation);}
        boolean reviewRequired=!reviewMatches||!state.effectiveValid()||!Set.of("approved","published").contains(publicationState);
        List<String> blocking=new ArrayList<>();if(!state.effectiveValid())blocking.add("invalid_evidence");if(reviewRequired)blocking.add("review_required");
        return new ContentView(version.id(),version.kind(),version.title(),version.revision(),version.bodyRevision(),publicationState,version.publishedAt(),version.inputOwner(),context.generation(),cutoff,coverage,reviewRequired,List.copyOf(blocking),state.hasValidityHistory(),relation.supersedesJobId(),metadata.interpretation(),body,List.copyOf(citations));
    }
    private static Coverage coverage(JsonNode node) {
        List<MissingInput> missing=new ArrayList<>();for(var item:node.get("missing_inputs"))missing.add(new MissingInput(WireValues.text(item.get("kind")),item.get("id").isNull()?null:WireValues.id(item.get("id"),false),item.get("business_date").isNull()?null:WireValues.businessDate(item.get("business_date")),WireValues.text(item.get("reason"))));
        return new Coverage(WireValues.text(node.get("state")),List.copyOf(missing),dates(node.get("missing_dates")),dates(node.get("no_update_dates")));
    }
    private static List<LocalDate> dates(JsonNode node){List<LocalDate> result=new ArrayList<>();node.forEach(value->result.add(WireValues.businessDate(value)));return List.copyOf(result);}
    private static IllegalStateException unavailable(){return new IllegalStateException("frozen public content proof unavailable");}
}
