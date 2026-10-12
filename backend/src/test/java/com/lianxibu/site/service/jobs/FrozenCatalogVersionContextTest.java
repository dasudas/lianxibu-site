package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Fictional proof references qualify only codec/persistence behavior, never source admission. */
class FrozenCatalogVersionContextTest {
    ObjectNode context(Stage stage){var value=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(stage));value.put("dedup_result_version","2");var refs=value.putArray("catalog_version_qualifications");var entry=refs.addObject().put("item_id","fictional-item").put("entity_id","fictional-entity").put("version_changed",true);entry.putObject("previous_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash","a".repeat(64));entry.putObject("new_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash","b".repeat(64));return value;}
    @Test void FourCatalogStagesBindClosedOriginalFlagsReferencesAndDefensiveCanonicalValues(){
        for(Stage stage:List.of(Stage.AGENT_DEDUP,Stage.SKILL_DEDUP,Stage.WORKFLOW_DEDUP,Stage.MODEL_DEDUP)){
            var raw=context(stage);var frozen=FrozenApplicationContext.fromJson(raw);var input=frozen.input("fictional-root","fictional-attempt","c".repeat(64),4);assertEquals("2",input.dedupResultVersion());assertEquals("1",input.valueResultVersion());assertEquals(1,input.catalogVersionQualifications().size());
            var q=input.catalogVersionQualifications().get("fictional-item");assertTrue(q.versionChanged());assertEquals("a".repeat(64),q.previousProofHash());assertEquals("b".repeat(64),q.newProofHash());
            raw.put("generation",2);assertEquals(1,frozen.generation());var copy=(ObjectNode)frozen.toJson();copy.put("generation",3);assertEquals(1,frozen.generation());assertThrows(UnsupportedOperationException.class,()->input.catalogVersionQualifications().clear());assertFalse(q.toString().contains("fictional"));
        }
    }
    @Test void MissingFlagsForeignStageDanglingCandidateUnknownFieldsAndDuplicateItemReject(){
        var missing=context(Stage.AGENT_DEDUP);missing.remove("catalog_version_qualifications");assertThrows(RuntimeException.class,()->FrozenApplicationContext.fromJson(missing));
        var orphan=context(Stage.AGENT_DEDUP);orphan.remove("dedup_result_version");assertThrows(RuntimeException.class,()->FrozenApplicationContext.fromJson(orphan));
        for(Stage stage:List.of(Stage.NEWS_DEDUP,Stage.NEWS_VALUE,Stage.AGENT_ANALYSIS,Stage.DAILY_REPORT))assertThrows(RuntimeException.class,()->FrozenApplicationContext.fromJson(context(stage)));
        var foreign=context(Stage.AGENT_DEDUP);((ObjectNode)foreign.get("catalog_version_qualifications").get(0)).put("entity_id","fictional-foreign");assertThrows(RuntimeException.class,()->FrozenApplicationContext.fromJson(foreign));
        var extra=context(Stage.AGENT_DEDUP);((ObjectNode)extra.get("catalog_version_qualifications").get(0)).put("fictional_unknown",true);assertThrows(RuntimeException.class,()->FrozenApplicationContext.fromJson(extra));
        var duplicate=context(Stage.AGENT_DEDUP);duplicate.withArray("catalog_version_qualifications").add(duplicate.get("catalog_version_qualifications").get(0).deepCopy());assertThrows(RuntimeException.class,()->FrozenApplicationContext.fromJson(duplicate));
    }
    @Test void KnownFalseAndUnknownScopesCannotClaimChangedEligibility(){
        var value=context(Stage.AGENT_DEDUP);((ObjectNode)value.get("catalog_version_qualifications").get(0)).put("version_changed",false);assertThrows(RuntimeException.class,()->FrozenApplicationContext.fromJson(value));
        value.putArray("version_eligible_item_ids");var input=FrozenApplicationContext.fromJson(value).input("fictional-root","fictional-attempt","c".repeat(64),0);assertFalse(input.catalogVersionQualifications().get("fictional-item").versionChanged());
        value.putArray("catalog_version_qualifications");assertTrue(FrozenApplicationContext.fromJson(value).input("fictional-root","fictional-attempt","c".repeat(64),0).catalogVersionQualifications().isEmpty());
    }
    @Test void WrongProofKindHashNullSameSideAndStringBooleanCannotFreeze(){
        for(String fault:List.of("kind","hash","null","same","flag")){
            var value=context(Stage.AGENT_DEDUP);var q=(ObjectNode)value.get("catalog_version_qualifications").get(0);var ref=(ObjectNode)q.get("new_evidence_ref");
            switch(fault){case "kind"->ref.put("kind","catalog_evidence");case "hash"->ref.put("proof_hash","B".repeat(64));case "null"->q.putNull("new_evidence_ref");case "same"->ref.put("proof_hash","a".repeat(64));case "flag"->q.put("version_changed","true");}
            assertThrows(RuntimeException.class,()->FrozenApplicationContext.fromJson(value));
        }
    }
    @Test void OriginalV1AndNewsValueV2RetainTheirIndependentDefaultVersions(){
        var old=FrozenApplicationContext.fromJson(CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.AGENT_DEDUP))).input("fictional-root","fictional-attempt","c".repeat(64),0);assertEquals("1",old.dedupResultVersion());assertTrue(old.catalogVersionQualifications().isEmpty());
        var news=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.NEWS_VALUE));news.put("value_result_version","2");var value=FrozenApplicationContext.fromJson(news).input("fictional-root","fictional-attempt","c".repeat(64),0);assertEquals("2",value.valueResultVersion());assertEquals("1",value.dedupResultVersion());
    }
    @Test void V2OriginalInputCannotAcceptAnOriginalV1ResultPayload(){
        var input=FrozenApplicationContext.fromJson(context(Stage.AGENT_DEDUP)).input("fictional-root","fictional-attempt","c".repeat(64),0);
        String payload="{\"kind\":\"dedup\",\"items\":[{\"item_id\":\"fictional-item\",\"category\":\"agent\",\"decision\":\"existing\",\"candidate_entity_id\":\"fictional-entity\",\"reason\":\"Fictional comparison\",\"confidence\":\"1\",\"suggested_title\":\"Fictional object\",\"suggested_summary\":null,\"reanalyze_recommended\":false}],\"summary\":\"Fictional source summary\"}";
        var old=FrozenApplicationContext.fromJson(CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.AGENT_DEDUP))).input("fictional-root","fictional-attempt","c".repeat(64),0);String raw=ResultApplicationService.providerEnvelope(old,payload);assertThrows(ResultApplicationService.Rejected.class,()->ResultApplicationService.validate(input,raw));
    }
}
