package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderResultEnvelopeTest {
    ResultApplicationService.Input input(Stage stage){return FrozenApplicationContext.fromJson(CanonicalJson.parse(FrozenApplicationContextTest.context(stage))).input("fictional-root","fictional-attempt","a".repeat(64),1);}
    String envelope(Stage stage,String payload){return ResultApplicationService.providerEnvelope(input(stage),payload);}
    @Test void fivePayloadKindsUseServerInputIdentityAndCanonicalHash() throws Exception {
        var payloads=new LinkedHashMap<Stage,String>();
        payloads.put(Stage.NEWS_DEDUP,"{\"kind\":\"dedup\",\"summary\":\"Fictional summary\",\"items\":[{\"item_id\":\"fictional-item\",\"category\":\"news_tech\",\"decision\":\"new\",\"candidate_entity_id\":null,\"reason\":\"Fictional reason\",\"confidence\":\"0.9\",\"suggested_title\":\"Fictional title\",\"suggested_summary\":null,\"reanalyze_recommended\":false}]}");
        payloads.put(Stage.NEWS_VALUE,"{\"kind\":\"value\",\"summary\":\"Fictional summary\",\"items\":[{\"item_id\":\"fictional-item\",\"category\":\"news_tech\",\"high_value\":true,\"reason\":\"Fictional reason\"}],\"selected_item_ids\":[\"fictional-item\"]}");
        payloads.put(Stage.AGENT_ANALYSIS,"{\"kind\":\"analysis\",\"body\":{\"title\":\"Fictional analysis\",\"sections\":[],\"citation_ids\":[],\"category\":\"agent\",\"entity_id\":\"fictional-entity\"}}");
        payloads.put(Stage.DAILY_REPORT,"{\"kind\":\"report\",\"body\":"+ResultJdbcStoreTest.BODY+"}");
        payloads.put(Stage.AD_HOC_AI,"{\"kind\":\"ad_hoc\",\"title\":\"Fictional answer\",\"sections\":[],\"citation_ids\":[]}");
        for(var item:payloads.entrySet()) {
            var wire=envelope(item.getKey(),item.getValue());var validated=ResultApplicationService.validate(input(item.getKey()),wire);
            assertEquals("a".repeat(64),validated.inputHash());assertEquals(CanonicalJson.parse(item.getValue()),CanonicalJson.parse(validated.payloadJson()));
            var core=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(wire);String hash=core.remove("result_hash").asText();
            var digest=java.security.MessageDigest.getInstance("SHA-256");digest.update("ai-result-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update(CanonicalJson.bytes(core));assertEquals(HexFormat.of().formatHex(digest.digest()),hash);
            assertEquals(wire,envelope(item.getKey()," \n"+item.getValue()+"\n"));
        }
    }
    @Test void duplicateKeysMarkdownExtraEnvelopeAndUnknownCitationsAreNotRepaired() {
        String valid="{\"kind\":\"ad_hoc\",\"title\":\"Fictional\",\"sections\":[],\"citation_ids\":[]}";
        for(String raw:List.of("```json\n"+valid+"\n```",valid+"{}",valid.replace("\"kind\":","\"kind\":\"ad_hoc\",\"kind\":"),valid.replace("[]","[\"fictional-unknown\"]"),valid.replace("\"sections\":[]","\"sections\":1"),"{\"schema_version\":\"1\",\"input_hash\":\""+"b".repeat(64)+"\",\"payload\":"+valid+"}","x".repeat(256*1024+1)))assertThrows(ResultApplicationService.Rejected.class,()->envelope(Stage.AD_HOC_AI,raw));
        assertThrows(ResultApplicationService.Rejected.class,()->envelope(Stage.NEWS_DEDUP,valid));
    }
}
