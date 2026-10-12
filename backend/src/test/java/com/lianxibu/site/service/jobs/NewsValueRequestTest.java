package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import org.junit.jupiter.api.Test;

class NewsValueRequestTest {
    static tools.jackson.databind.JsonNode request(tools.jackson.databind.JsonNode input){return CanonicalJson.parse(new GenerationRequestBuilder().build(GenerationFlowService.Stage.NEWS_VALUE,JdbcFrozenInputs.canonical(input),CanonicalJson.hash(CanonicalJson.Domain.INPUT,input)));}
    @Test void exactProjectedMessageUsesOriginalInputHashAndANewPromptVersion(){
        var input=NewsValueInputProfileTest.snapshot(null,"😀".repeat(800)+"Fictional excluded body suffix");String original=JdbcFrozenInputs.canonical(input);var request=request(input);var message=CanonicalJson.parse(request.get("messages").get(1).get("content").asText());
        assertEquals(JdbcFrozenInputs.canonical(NewsValueInputProfile.modelView(input)),request.get("messages").get(1).get("content").asText());assertEquals("site-value-material-v1",request.get("prompt_version").asText());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,input),request.get("input_hash").asText());assertEquals(original,JdbcFrozenInputs.canonical(input));
        assertFalse(JdbcFrozenInputs.canonical(request).contains("excluded body suffix"));assertTrue(request.get("messages").get(0).get("content").asText().contains("news_value_input_v1"));assertTrue(JdbcFrozenInputs.canonical(request).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1048576);
    }
    @Test void fullSummaryAndMissingStayExplicitWithOriginalValueSchema(){
        String summary="Fictional long summary "+"x".repeat(2400);var request=request(NewsValueInputProfileTest.snapshot(summary,"Fictional unused body"));var message=CanonicalJson.parse(request.get("messages").get(1).get("content").asText());assertEquals(summary,message.get(NewsValueInputProfile.FIELD).get("materials").get(0).get("text").asText());assertFalse(JdbcFrozenInputs.canonical(request).contains("unused body"));assertEquals("#/$defs/ValueResult",request.get("json_schema").get("$ref").asText());
        assertEquals("missing",CanonicalJson.parse(request(NewsValueInputProfileTest.snapshot(null,null)).get("messages").get(1).get("content").asText()).get(NewsValueInputProfile.FIELD).get("materials").get(0).get("input_basis").asText());
    }
    @Test void realSourceMemberCannotBypassMaterialProofOrMixStages(){
        var missing=NewsValueInputProfileTest.snapshot(null,"Fictional body");missing.remove(NewsValueInputProfile.FIELD);assertThrows(RuntimeException.class,()->request(missing));
        var altered=NewsValueInputProfileTest.snapshot(null,"Fictional body");((tools.jackson.databind.node.ObjectNode)altered.get(NewsValueInputProfile.FIELD).get("materials").get(0)).put("text","Fictional substituted");assertThrows(RuntimeException.class,()->request(altered));
        var stage=NewsValueInputProfileTest.snapshot(null,"Fictional body");stage.put("stage","news_dedup");((tools.jackson.databind.node.ObjectNode)stage.get(FrozenApplicationContext.FIELD)).put("stage","news_dedup");assertThrows(RuntimeException.class,()->new GenerationRequestBuilder().build(GenerationFlowService.Stage.NEWS_DEDUP,JdbcFrozenInputs.canonical(stage),CanonicalJson.hash(CanonicalJson.Domain.INPUT,stage)));
    }
}
