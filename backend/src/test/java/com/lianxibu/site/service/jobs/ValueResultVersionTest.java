package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ValueResultVersionTest {
    static ObjectNode context(String version) {
        var node=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.NEWS_VALUE));
        if(version!=null)node.put("value_result_version",version);return node;
    }
    static ResultApplicationService.Input input(String version) {
        return FrozenApplicationContext.fromJson(context(version)).input("fictional-value-root","fictional-attempt","a".repeat(64),0);
    }
    static ObjectNode payload(boolean valuable,boolean high) {
        var n=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","value").put("value_result_version","2").put("summary","Fictional summary");
        n.putArray("items").addObject().put("item_id","fictional-item").put("category","agent").put("valuable",valuable).put("high_value",high).put("reason","Fictional evidence reason");n.putArray("selected_item_ids");return n;
    }
    static String envelope(String version,ObjectNode payload) {return ResultApplicationService.providerEnvelope(input(version),payload.toString());}
    @Test void independentOrdinaryAndHighValueBooleansAreAccepted() {
        assertDoesNotThrow(()->envelope("2",payload(true,false)));assertDoesNotThrow(()->envelope("2",payload(false,false)));assertDoesNotThrow(()->envelope("2",payload(true,true)));
    }
    @Test void highValueWithoutOrdinaryValueIsRejectedEvenIfNotSelected() {assertThrows(RuntimeException.class,()->envelope("2",payload(false,true)));}
    @Test void missingStringValuableAndUnknownFieldReject() {
        for(String key:new String[]{"valuable","unrecognized"}) {var p=payload(true,false);var item=(ObjectNode)p.get("items").get(0);if(key.equals("valuable"))item.remove(key);else item.put(key,true);assertThrows(RuntimeException.class,()->envelope("2",p));}
        var p=payload(true,false);((ObjectNode)p.get("items").get(0)).put("valuable","true");assertThrows(RuntimeException.class,()->envelope("2",p));
    }
    @Test void originalInputVersionMustMatchExactPayload() {
        var old=payload(true,false);old.remove("value_result_version");((ObjectNode)old.get("items").get(0)).remove("valuable");assertDoesNotThrow(()->envelope(null,old));assertThrows(RuntimeException.class,()->envelope("2",old));assertThrows(RuntimeException.class,()->envelope(null,payload(true,false)));
        var p=payload(true,false);p.put("value_result_version","1");assertThrows(RuntimeException.class,()->envelope("2",p));
        var numeric=payload(true,false);numeric.put("value_result_version",2);assertThrows(RuntimeException.class,()->envelope("2",numeric));
    }
    @Test void versionIsClosedAndOnlyValueStageMayOptIn() {
        assertThrows(RuntimeException.class,()->input("1"));assertThrows(RuntimeException.class,()->input("3"));assertThrows(RuntimeException.class,()->input("2\n"));
        var p=context("2");p.put("stage","news_dedup");assertThrows(RuntimeException.class,()->FrozenApplicationContext.fromJson(p));
    }
    @Test void missingVersionPreservesOriginalCanonicalContextAndDifferentInputsDiffer() {
        var original=context(null);var legacy=FrozenApplicationContext.fromJson(original);assertEquals(new String(CanonicalJson.bytes(original),java.nio.charset.StandardCharsets.UTF_8),legacy.json());
        assertNotEquals(input(null),input("2"));assertEquals(legacy.json(),FrozenApplicationContext.fromJson(CanonicalJson.parse(legacy.json())).json());
    }
}
