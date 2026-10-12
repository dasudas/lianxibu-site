package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ValueVersionRequestTest {
    static ObjectNode snapshot(boolean v2) {
        var f=new NewsValueSourcePlanBuilderTest();var x=f.fixture(2);var plan=f.plan(x,new FrozenGenerationSettings(1,java.util.List.of(),java.util.List.of()));
        var s=(ObjectNode)CanonicalJson.parse(plan.chunks().getFirst().snapshotJson());if(v2)((ObjectNode)s.get(FrozenApplicationContext.FIELD)).put("value_result_version","2");return s;
    }
    @Test void originalV2InputSelectsExactSchemaPromptAndProjectedMessage() {
        var s=snapshot(true);String original=JdbcFrozenInputs.canonical(s);var r=NewsValueRequestTest.request(s);
        assertEquals("site-value-material-v2",r.get("prompt_version").asText());assertEquals("#/$defs/ValueResultV2",r.get("json_schema").get("$ref").asText());
        assertTrue(r.get("json_schema").get("$defs").get("ValueDecisionV2").get("properties").get("valuable").get("type").asText().equals("boolean"));
        assertTrue(r.get("messages").get(0).get("content").asText().contains("valuable"));assertEquals(JdbcFrozenInputs.canonical(NewsValueInputProfile.modelView(s)),r.get("messages").get(1).get("content").asText());assertEquals(original,JdbcFrozenInputs.canonical(s));assertEquals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,s),r.get("input_hash").asText());
    }
    @Test void unmarkedOriginalStillUsesV1SchemaAndPrompt() {
        var r=NewsValueRequestTest.request(snapshot(false));assertEquals("site-value-material-v1",r.get("prompt_version").asText());assertEquals("#/$defs/ValueResult",r.get("json_schema").get("$ref").asText());assertFalse(r.get("json_schema").get("$defs").get("ValueDecision").get("properties").has("valuable"));
    }
    @Test void V2CannotUseAnIsolatedMaterialOnlyInput() {
        var s=NewsValueInputProfileTest.snapshot(null,"Fictional body");((ObjectNode)s.get(FrozenApplicationContext.FIELD)).put("value_result_version","2");assertThrows(RuntimeException.class,()->NewsValueRequestTest.request(s));
        var complete=snapshot(true);complete.remove(FrozenSourceDedupDependency.FIELD);assertThrows(RuntimeException.class,()->NewsValueRequestTest.request(complete));
    }
}
