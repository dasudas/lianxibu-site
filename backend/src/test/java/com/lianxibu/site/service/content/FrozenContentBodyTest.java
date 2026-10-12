package com.lianxibu.site.service.content;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenContentBodyTest {
    static ObjectNode body(){return (ObjectNode)CanonicalJson.parse("""
        {"title":"Fictional title","sections":[{"heading":"Fictional section","text":"Fictional content","citation_ids":["fictional-citation"]}],"citation_ids":["fictional-citation"],"category":"agent","entity_id":"fictional-entity"}
        """);}
    @Test void exactInterpretationRoundTripAndImmutableLists(){var original=body();var result=(ContentService.InterpretationBody)FrozenContentBody.read("agent",original);assertEquals(original,PublishedContentFacade.body(result));original.put("title","Fictional later title");assertEquals("Fictional title",result.title());assertThrows(UnsupportedOperationException.class,()->result.sections().clear());}
    @Test void exactReportAndLearningTaskRoundTrip(){var original=body();original.remove("category");original.remove("entity_id");original.put("type","daily");original.put("period_start","2026-01-01T00:00:00.000000Z");original.put("period_end","2026-01-02T00:00:00.000000Z");original.put("timezone","Asia/Shanghai");original.set("learning_task",CanonicalJson.parse("{\"package_id\":\"fictional-package\",\"object_kind\":\"node\",\"object_id\":\"fictional-node\",\"title\":\"Fictional lesson\",\"reason\":\"Fictional reason\"}"));assertEquals(original,PublishedContentFacade.body(FrozenContentBody.read("report",original)));}
    @Test void wrongKindAndUnknownFieldsFail(){assertThrows(RuntimeException.class,()->FrozenContentBody.read("model",body()));var extra=body();extra.put("private_input","Fictional forbidden extra");assertThrows(RuntimeException.class,()->FrozenContentBody.read("agent",extra));}
    @Test void contractAllowsRepeatedIdsAndIndependentSectionReferences(){var independent=body();independent.putArray("citation_ids");assertEquals(independent,PublishedContentFacade.body(FrozenContentBody.read("agent",independent)));var repeated=body();((tools.jackson.databind.node.ArrayNode)repeated.get("citation_ids")).add("fictional-citation");assertEquals(repeated,PublishedContentFacade.body(FrozenContentBody.read("agent",repeated)));}
}
