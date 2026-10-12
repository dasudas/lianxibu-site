package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class CatalogVersionEvidenceMarkerTest {
    ObjectNode catalog(String category){
        var n=JsonMapper.shared().createObjectNode().put("schema_version","1").put("parser_version","fictional-parser-v1").put("config_version",1).put("category",category).put("title","Fictional object").put("source_url","https://example.invalid/object").put("version","v1").putNull("published_at").putNull("updated_at").put("summary","Fictional summary").put("document_text","Fictional document").put("truncated",false);
        n.putObject("identity").put("namespace",category.equals("skill")?"github_skill":category.equals("model")?"model_registry":"github_repository").put("key","fictional-object");
        n.putArray("evidence").addObject().put("url","https://example.invalid/object/releases/v1").put("revision","v1").putNull("path").putNull("file_hash").put("evidence_type","release");return n;
    }
    ObjectNode skill(){var n=catalog("skill");n.put("version","a".repeat(40));n.putArray("evidence").addObject().put("url","https://example.invalid/object/skills/example/SKILL.md").put("revision","a".repeat(40)).put("path","skills/example/SKILL.md").put("file_hash","b".repeat(64)).put("evidence_type","skill_document");return n;}
    ObjectNode metric(){
        var n=JsonMapper.shared().createObjectNode().put("schema_version","1").put("parser_version","fictional-parser-v1").put("config_version",1).put("benchmark","fictional-benchmark").put("benchmark_version","2026-09").put("task","fictional-reasoning").put("score","71").put("unit","percent").put("scale_min","0").put("scale_max","100").put("higher_is_better",true).putNull("evaluated_at").put("evaluation_scope","entity").put("provenance","third_party");n.putObject("subject").put("category","model").putObject("identity").put("namespace","model_registry").put("key","fictional-model");var config=n.putObject("evaluation_config").putNull("agent").put("model","fictional-model");config.putArray("skills");config.putObject("parameters");n.putArray("evidence").addObject().put("url","https://example.invalid/scores.json").put("revision","a".repeat(40)).put("path","scores.json").put("file_hash","b".repeat(64)).put("evidence_type","benchmark");return n;
    }
    CatalogVersionEvidenceMarker.Marker read(JsonNode n){return CatalogVersionEvidenceMarker.read(n,"fictional-source").orElseThrow();}
    @Test void FourClosedSubjectsHaveExactReleaseOrDocumentMarkersWithoutClaimingNativeAuthority(){
        for(String category:List.of("agent","skill","workflow","model")){var n=category.equals("skill")?skill():catalog(category);var first=read(n);assertEquals(category,first.category());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,CanonicalJson.parse(first.json())),first.hash());assertFalse(CatalogVersionEvidenceMarker.changed(first,read(n.deepCopy())));assertFalse(first.toString().contains("fictional-object"));}
    }
    @Test void ActualReleaseMarkerChangesWhileNarrativeAndCollectionMetadataDoNot(){
        for(String category:List.of("agent","workflow","model")){var n=catalog(category);var before=read(n);n.put("summary","Fictional revised summary").put("document_text","Fictional non-version text").put("config_version",9).put("updated_at","2026-09-02T00:00:00.000000Z");assertFalse(CatalogVersionEvidenceMarker.changed(before,read(n)));n.put("version","v2");((ObjectNode)n.get("evidence").get(0)).put("revision","v2");assertTrue(CatalogVersionEvidenceMarker.changed(before,read(n)));}
    }
    @Test void SkillCommitAloneIsStableAndOnlyExactOriginalDocumentBytesChangeItsVersion(){
        var n=skill();var before=read(n);n.put("version","c".repeat(40));((ObjectNode)n.get("evidence").get(0)).put("revision","c".repeat(40));assertFalse(CatalogVersionEvidenceMarker.changed(before,read(n)));((ObjectNode)n.get("evidence").get(0)).put("file_hash","d".repeat(64));assertTrue(CatalogVersionEvidenceMarker.changed(before,read(n)));
    }
    @Test void MissingReleaseOrSkillHashIsUnknownAndContradictoryEvidenceIsRejected(){
        var n=catalog("agent");n.putNull("version");assertTrue(CatalogVersionEvidenceMarker.read(n,"fictional-source").isEmpty());n=catalog("agent");((ObjectNode)n.get("evidence").get(0)).put("evidence_type","metadata");assertTrue(CatalogVersionEvidenceMarker.read(n,"fictional-source").isEmpty());var missing=skill();((ObjectNode)missing.get("evidence").get(0)).putNull("file_hash");assertTrue(CatalogVersionEvidenceMarker.read(missing,"fictional-source").isEmpty());var bad=catalog("workflow");bad.put("version","v2");assertThrows(RuntimeException.class,()->read(bad));
    }
    @Test void SkillWrongPathTraversalWrongCommitAndMultipleOriginalDocumentsCannotGrantMarker(){
        for(String path:List.of("README.md","/SKILL.md","skills/../SKILL.md","skills//SKILL.md","skills\\SKILL.md")){var n=skill();((ObjectNode)n.get("evidence").get(0)).put("path",path);assertThrows(RuntimeException.class,()->read(n));}var n=skill();((ObjectNode)n.get("evidence").get(0)).put("revision","main");assertThrows(RuntimeException.class,()->read(n));var multi=skill();multi.withArray("evidence").addObject().put("url","https://example.invalid/other/SKILL.md").put("revision","a".repeat(40)).put("path","other/SKILL.md").put("file_hash","b".repeat(64)).put("evidence_type","skill_document");assertThrows(RuntimeException.class,()->read(multi));
    }
    @Test void SameMetricRowIgnoresRepeatedOrUnrelatedWholeFileChangesAndTracksKnownScoreAndRelease(){
        var n=metric();var before=read(n);((ObjectNode)n.get("evidence").get(0)).put("revision","c".repeat(40)).put("file_hash","d".repeat(64));n.put("config_version",2).put("score","71.0").put("scale_max","100.00");assertFalse(CatalogVersionEvidenceMarker.changed(before,read(n)));n.put("score","72");assertTrue(CatalogVersionEvidenceMarker.changed(before,read(n)));n.put("score","71").put("benchmark_version","2026-10");assertTrue(CatalogVersionEvidenceMarker.changed(before,read(n)));
    }
    @Test void AggregationUnknownOriginalBenchmarkOrMissingScoreAndNativeFileProofStayUnknown(){
        var n=metric();n.put("benchmark_version","aggregation_snapshot:"+"a".repeat(40));assertTrue(CatalogVersionEvidenceMarker.read(n,"fictional-source").isEmpty());n=metric();((ObjectNode)n.get("evaluation_config").get("parameters")).put("original_benchmark_version","unknown");assertTrue(CatalogVersionEvidenceMarker.read(n,"fictional-source").isEmpty());n=metric();n.putNull("score");assertTrue(CatalogVersionEvidenceMarker.read(n,"fictional-source").isEmpty());n=metric();((ObjectNode)n.get("evidence").get(0)).putNull("file_hash");assertTrue(CatalogVersionEvidenceMarker.read(n,"fictional-source").isEmpty());
    }
    @Test void ForeignIdentityCategoryMalformedPayloadAndInvalidSourceAreRejected(){
        var agent=read(catalog("agent"));assertThrows(RuntimeException.class,()->CatalogVersionEvidenceMarker.changed(agent,read(catalog("workflow"))));var other=catalog("agent");((ObjectNode)other.get("identity")).put("key","fictional-other");assertThrows(RuntimeException.class,()->CatalogVersionEvidenceMarker.changed(agent,read(other)));assertThrows(RuntimeException.class,()->CatalogVersionEvidenceMarker.changed(null,agent));var malformed=catalog("agent");malformed.put("unexpected",true);assertThrows(RuntimeException.class,()->read(malformed));assertThrows(RuntimeException.class,()->CatalogVersionEvidenceMarker.read(catalog("agent"),""));assertThrows(RuntimeException.class,()->read(JsonMapper.shared().createObjectNode()));
    }
}
