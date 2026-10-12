package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.FrozenPublicMetadata;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class AnalysisPublicMetadataEnricherTest {
    EntityInputEvidenceService entities=mock(EntityInputEvidenceService.class);ArticlePublicEvidenceService articles=mock(ArticlePublicEvidenceService.class);
    AnalysisPublicMetadataEnricher enricher=new AnalysisPublicMetadataEnricher(entities,articles);
    Instant cutoff=Instant.parse("2026-01-02T00:00:00Z");ObjectNode snapshot,payload,target;FrozenApplicationContext context;GenerationFlowService.Prepare command;
    void setup(boolean metric) {
        String category=metric?"model":"agent";var stage=metric?GenerationFlowService.Stage.MODEL_ANALYSIS:GenerationFlowService.Stage.AGENT_ANALYSIS;
        payload=(ObjectNode)CanonicalJson.parse(metric?"""
            {"subject":{"category":"model","identity":{"namespace":"model_registry","key":"fictional-model"}},"benchmark":"Fictional benchmark","benchmark_version":"v1","task":"Fictional task","score":null,"unit":"points","evaluated_at":null,"higher_is_better":true,"evaluation_scope":"configuration","provenance":"third_party","evidence":[{"url":"https://example.invalid/benchmark","evidence_type":"benchmark"}]}
            """:"""
            {"category":"agent","identity":{"namespace":"github_repository","key":"fictional/agent"},"title":"Fictional catalog title","summary":"Fictional summary","source_url":"https://example.invalid/agent","published_at":null,"updated_at":"2026-01-01T12:00:00.000000Z"}
            """);
        target=JsonMapper.shared().createObjectNode().put("kind","catalog_evidence").put("item_id","fictional-item");
        var ctx=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(stage));ctx.putArray("reference_targets").add(target);ctx.putArray("allowed_citation_ids").add(AnalysisPublicMetadataEnricher.citationId(target));
        context=FrozenApplicationContext.fromJson(ctx);command=new GenerationFlowService.Prepare("fictional-key",1,stage,LocalDate.of(2026,1,1),null,List.of(),List.of(),List.of(),true,"a".repeat(64),context);
        snapshot=JsonMapper.shared().createObjectNode();snapshot.set(FrozenApplicationContext.FIELD,ctx);snapshot.putArray("members").addObject().put("id","fictional-item").put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,payload)).set("body",payload.deepCopy());
        var fact=new SourceMemberEvidenceService.Fact("fictional-item",3,category,"fictional-identity",metric?"fictional-model":"Fictional catalog title",metric?null:"Fictional summary",payload.toString());
        when(entities.read("fictional-entity",category,Set.of("fictional-item"),cutoff)).thenReturn(new EntityInputEvidenceService.Selection("fictional-entity",category,2,List.of(fact)));
    }
    @Test void catalogProfileAndCitationComeOnlyFromSelectedOriginalEvidence(){setup(false);var extension=enricher.fields("fictional-root",command,cutoff,snapshot);var result=FrozenPublicMetadata.read(extension.get(FrozenPublicMetadata.FIELD),context);assertEquals("Fictional catalog title",result.interpretation().name());assertNull(result.interpretation().provider());assertNull(result.interpretation().stars());assertEquals("https://example.invalid/agent",result.citations().getFirst().sourceUrl());assertEquals(Instant.parse("2026-01-01T12:00:00Z"),result.interpretation().evidenceTime());verifyNoInteractions(articles);}
    @Test void metricUnknownScoreAndConfigurationScopeArePreserved(){setup(true);var result=FrozenPublicMetadata.read(enricher.fields("fictional-root",command,cutoff,snapshot).get(FrozenPublicMetadata.FIELD),context);assertEquals("fictional-model",result.interpretation().name());assertNull(result.interpretation().metrics().getFirst().score());assertEquals("configuration",result.interpretation().metrics().getFirst().evaluationScope());assertEquals("https://example.invalid/benchmark",result.citations().getFirst().sourceUrl());}
    @Test void changedBodyMissingReferenceAndWrongSelectionReject(){setup(false);((ObjectNode)snapshot.get("members").get(0).get("body")).put("title","Fictional altered");assertThrows(RuntimeException.class,()->enricher.fields("fictional-root",command,cutoff,snapshot));setup(false);((ObjectNode)snapshot.get(FrozenApplicationContext.FIELD)).putArray("reference_targets");assertThrows(RuntimeException.class,()->enricher.fields("fictional-root",command,cutoff,snapshot));setup(false);when(entities.read("fictional-entity","agent",Set.of("fictional-item"),cutoff)).thenReturn(new EntityInputEvidenceService.Selection("fictional-other","agent",1,List.of()));assertThrows(RuntimeException.class,()->enricher.fields("fictional-root",command,cutoff,snapshot));}
}
