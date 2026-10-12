package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ReportPublicMetadataEnricherTest {
    ArticlePublicEvidenceService articles=mock(ArticlePublicEvidenceService.class);
    SourceMemberEvidenceService members=mock(SourceMemberEvidenceService.class);
    FrozenContentVersionService versions=mock(FrozenContentVersionService.class);
    ReportPublicMetadataEnricher enricher=new ReportPublicMetadataEnricher(articles,members,versions);
    Instant cutoff=ReportInputSnapshotServiceTest.CUTOFF;
    ObjectNode snapshot; FrozenApplicationContext context; Prepare command;
    <T> T tx(java.util.function.Supplier<T> operation) {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID());
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->operation.get());
    }
    void setup(List<JsonNode> targets) {
        var base=ReportInputSnapshotServiceTest.command(Stage.DAILY_REPORT,ReportInputSnapshotServiceTest.DAY);
        var frozen=ReportInputSnapshotServiceTest.freeze(base,cutoff,List.of(),List.of(ReportInputSnapshotServiceTest.DAY));
        snapshot=(ObjectNode)CanonicalJson.parse(frozen.snapshotJson());
        var ctx=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.DAILY_REPORT));ctx.put("generation",base.generation());
        var refs=ctx.putArray("reference_targets");var ids=ctx.putArray("allowed_citation_ids");
        for(var target:targets){refs.add(target);ids.add(AnalysisPublicMetadataEnricher.citationId(target));}
        context=FrozenApplicationContext.fromJson(ctx);snapshot.set(FrozenApplicationContext.FIELD,context.toJson());
        command=new Prepare(base.logicalKey(),base.generation(),base.stage(),base.businessDate(),null,List.of(),List.of(),List.of(),false,base.settingsHash(),context);
    }
    FrozenPublicMetadata.Metadata result(){return tx(()->FrozenPublicMetadata.read(enricher.fields("fictional-root",command,cutoff,snapshot).get(FrozenPublicMetadata.FIELD),context));}
    @Test void exactArticleAndCatalogFactsSupplyOnlyProvenTitlesAndUrls() {
        var article=CanonicalJson.parse("{\"kind\":\"article_revision\",\"id\":\"fictional-revision\"}");
        var catalog=CanonicalJson.parse("{\"kind\":\"catalog_evidence\",\"item_id\":\"fictional-item\"}");setup(List.of(article,catalog));
        when(articles.read("fictional-revision",cutoff)).thenReturn(new ArticlePublicEvidenceService.Fact("fictional-revision","Fictional article","https://example.invalid/article"));
        when(members.read(Set.of("fictional-item"),cutoff)).thenReturn(List.of(new SourceMemberEvidenceService.Fact("fictional-item",1,"agent","fictional-id","Fictional agent",null,"{\"source_url\":\"https://example.invalid/agent\"}")));
        var result=result();assertNull(result.interpretation());assertEquals(2,result.citations().size());
        assertEquals(Set.of("Fictional article","Fictional agent"),new HashSet<>(result.citations().stream().map(ContentService.Citation::title).toList()));verifyNoInteractions(versions);
    }
    @Test void contentCitationUsesExactBodyAndNeverInventsPublicUrl() {
        var body=CanonicalJson.parse("{\"title\":\"Fictional original title\",\"sections\":[],\"citation_ids\":[],\"category\":\"agent\",\"entity_id\":\"fictional-entity\"}");
        var key=new ContentVersionKey("agent","fictional-analysis",1,CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body),"fictional-input");
        var target=CanonicalJson.parse(com.lianxibu.site.repository.jobs.JdbcFrozenContentVersions.version(key));setup(List.of(target));
        var prior=FrozenApplicationContext.fromJson(CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.AGENT_ANALYSIS)));
        when(versions.findAt(key,cutoff)).thenReturn(Optional.of(new FrozenContentVersionService.Proven(key,prior,"a".repeat(64),body.toString(),"{}",cutoff)));
        var citation=result().citations().getFirst();assertEquals("Fictional original title",citation.title());assertNull(citation.sourceUrl());assertEquals(1,citation.revision());assertEquals(key.bodyHash(),citation.bodyHash());
        when(versions.findAt(key,cutoff)).thenReturn(Optional.empty());assertThrows(RuntimeException.class,this::result);
    }
    @Test void unknownMetricUrlRemainsNullAndWrongMemberIdentityRejects() {
        var target=CanonicalJson.parse("{\"kind\":\"catalog_evidence\",\"item_id\":\"fictional-item\"}");setup(List.of(target));
        var fact=new SourceMemberEvidenceService.Fact("fictional-item",1,"model","fictional-model","Fictional model",null,"{\"subject\":{},\"evidence\":[{\"evidence_type\":\"benchmark\",\"url\":\"https://example.invalid/one\"},{\"evidence_type\":\"benchmark\",\"url\":\"https://example.invalid/two\"}]}");
        when(members.read(Set.of("fictional-item"),cutoff)).thenReturn(List.of(fact));assertNull(result().citations().getFirst().sourceUrl());
        when(members.read(Set.of("fictional-item"),cutoff)).thenReturn(List.of(new SourceMemberEvidenceService.Fact("fictional-other",1,"model","fictional-model","Fictional model",null,fact.payloadJson())));assertThrows(RuntimeException.class,this::result);
    }
    @Test void emptyReferencesAcceptButWrongPeriodCutoffContextOrNoTransactionReject() {
        setup(List.of());assertTrue(result().citations().isEmpty());verifyNoInteractions(articles,members,versions);
        assertThrows(RuntimeException.class,()->enricher.fields("fictional-root",command,cutoff,snapshot));
        assertThrows(RuntimeException.class,()->tx(()->enricher.fields("fictional-root",command,cutoff.plusSeconds(1),snapshot)));
        ((ObjectNode)snapshot.get(FrozenApplicationContext.FIELD)).put("generation",99);assertThrows(RuntimeException.class,this::result);
        setup(List.of());((ObjectNode)snapshot.get(ReportInputProfile.FIELD)).put("period_end","2026-01-02T16:00:00.000000Z");assertThrows(RuntimeException.class,this::result);
    }
    @Test void unmatchedCitationIdRejectsBeforeEvidenceLookup() {
        setup(List.of());var ctx=(ObjectNode)context.toJson();ctx.putArray("allowed_citation_ids").add("fictional-invented");context=FrozenApplicationContext.fromJson(ctx);
        command=new Prepare(command.logicalKey(),command.generation(),command.stage(),command.businessDate(),null,List.of(),List.of(),List.of(),false,command.settingsHash(),context);snapshot.set(FrozenApplicationContext.FIELD,context.toJson());
        assertThrows(RuntimeException.class,this::result);verifyNoInteractions(articles,members,versions);
    }
}
