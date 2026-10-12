package com.lianxibu.site.service.content;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.ContentVersionKey;
import com.lianxibu.site.service.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class FrozenPublishedProjectionTest {
    static final String TARGET="{\"kind\":\"article_revision\",\"id\":\"fictional-revision\"}";
    FrozenContentVersionService versions=mock(FrozenContentVersionService.class);
    ContentValiditySummaryService validity=mock(ContentValiditySummaryService.class);
    GenerationLineageService lineage=mock(GenerationLineageService.class);
    FrozenPublishedProjection projection=new FrozenPublishedProjection(versions,validity,lineage);
    ObjectNode root,body;FrozenApplicationContext context;ContentVersionKey key;PublishedContentFacade.Version version;
    void setup(boolean report){
        context=FrozenPublicMetadataTest.context(report,TARGET);
        body=(ObjectNode)CanonicalJson.parse("{\"title\":\"Fictional title\",\"sections\":[{\"heading\":\"Fictional heading\",\"text\":\"Fictional text\",\"citation_ids\":[\"fictional-citation\"]}],\"citation_ids\":[],\"category\":\"model\",\"entity_id\":\"fictional-entity\"}");
        if(report){
            body.remove("category");body.remove("entity_id");body.put("type","daily");body.put("period_start",context.periodStart());body.put("period_end",context.periodEnd());body.put("timezone","Asia/Shanghai");body.putNull("learning_task");
            var command=new GenerationFlowService.Prepare("fictional-key",1,GenerationFlowService.Stage.DAILY_REPORT,LocalDate.of(2026,1,1),null,List.of(),List.of(),List.of(),false,"a".repeat(64),context);
            var provider=new ReportInputSnapshotService((c,cutoff)->new ReportInputSnapshotService.SourceProof(cutoff,LocalDate.of(2026,1,1),LocalDate.of(2026,1,2),true,List.of(),List.of(),List.of(LocalDate.of(2026,1,1)),List.of()));
            root=(ObjectNode)CanonicalJson.parse(provider.freeze(command,Instant.parse("2026-01-02T00:00:00Z")).snapshotJson());
        }else{root=JsonMapper.shared().createObjectNode();root.put("schema_version","1");root.put("generation",1);root.put("stage","model_analysis");root.put("cutoff","2026-01-02T00:00:00.000000Z");root.put("coverage_state","complete");root.putArray("missing");}
        root.set(FrozenApplicationContext.FIELD,context.toJson());root.set(FrozenPublicMetadata.FIELD,FrozenPublicMetadataTest.metadata(report,TARGET));
        key=new ContentVersionKey(report?"report":"model","fictional-content",1,CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body),"fictional-root");
        version=new PublishedContentFacade.Version(key.contentKind(),key.contentId(),"Fictional title",4,1,Instant.parse("2026-01-03T00:00:00Z"),key.inputJobId(),key.bodyHash(),body.toString());
        proof();when(validity.read(key)).thenReturn(new ContentValiditySummaryService.Summary(true,true,2));when(lineage.read("fictional-root")).thenReturn(new GenerationLineageService.Lineage("fictional-root",1,null));
    }
    void proof(){when(versions.find(key)).thenReturn(Optional.of(new FrozenContentVersionService.Proven(key,context,CanonicalJson.hash(CanonicalJson.Domain.INPUT,root),body.toString(),root.toString())));}
    @Test void exactAnalysisUsesSectionReferencesAndFrozenProfile(){setup(false);var value=projection.project(version);assertEquals(1,value.citations().size());assertEquals("Fictional model",value.interpretation().name());assertTrue(value.affectedByCorrection());assertEquals("complete",value.coverage().state());assertEquals(body,PublishedContentFacade.body(value.body()));}
    @Test void reportUsesProvenNoUpdateDatesAndExactPeriod(){setup(true);var value=projection.project(version);assertEquals(List.of(LocalDate.of(2026,1,1)),value.coverage().noUpdateDates());assertNull(value.interpretation());assertEquals(body,PublishedContentFacade.body(value.body()));}
    @Test void missingMetadataInvalidEvidenceAndWrongLineageAreRejected(){setup(false);root.remove(FrozenPublicMetadata.FIELD);proof();assertThrows(RuntimeException.class,()->projection.project(version));setup(false);when(validity.read(key)).thenReturn(new ContentValiditySummaryService.Summary(false,true,2));assertThrows(RuntimeException.class,()->projection.project(version));setup(false);when(lineage.read("fictional-root")).thenReturn(new GenerationLineageService.Lineage("fictional-root",2,"fictional-prior"));assertThrows(RuntimeException.class,()->projection.project(version));}
    @Test void ownerCanInspectInvalidDraftWithoutRestoringPublicEligibility(){
        setup(false);when(validity.read(key)).thenReturn(new ContentValiditySummaryService.Summary(false,true,2));
        var value=projection.projectOwner(version,"draft",false);
        assertEquals("draft",value.publicationState());assertTrue(value.reviewRequired());
        assertTrue(value.blockingReasons().contains("invalid_evidence"));assertEquals(body,PublishedContentFacade.body(value.body()));
        assertThrows(RuntimeException.class,()->projection.project(version));
        root.remove(FrozenPublicMetadata.FIELD);proof();assertThrows(RuntimeException.class,()->projection.projectOwner(version,"draft",false));
    }
    @Test void ownerReviewStateDoesNotInferApprovalFromPublicationState(){
        setup(false);assertTrue(projection.projectOwner(version,"published",false).reviewRequired());
        var approved=projection.projectOwner(version,"approved",true);assertFalse(approved.reviewRequired());assertTrue(approved.blockingReasons().isEmpty());
        assertTrue(projection.projectOwner(version,"withdrawn",true).reviewRequired());
        assertThrows(IllegalArgumentException.class,()->projection.projectOwner(version,"invented",true));
    }
}
