package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResultValueEligibilityTest {
    private static ResultApplicationService.Input input() {
        return new ResultApplicationService.Input("fictional-value-root","fictional-attempt",GenerationFlowService.Stage.NEWS_VALUE,
                1,"a".repeat(64),0,null,Set.of("fictional-new","fictional-old"),Map.of(),Set.of("fictional-new"),Set.of(),Set.of(),null,null,null);
    }
    private static String payload(boolean oldHigh,String selected) {
        return "{\"kind\":\"value\",\"items\":[{\"item_id\":\"fictional-new\",\"category\":\"news_tech\",\"high_value\":true,\"reason\":\"Fictional new release\"},"
                +"{\"item_id\":\"fictional-old\",\"category\":\"news_tech\",\"high_value\":"+oldHigh+",\"reason\":\"Fictional old release\"}],\"selected_item_ids\":"+selected+",\"summary\":\"Fictional summary\"}";
    }
    @Test void oldHighCannotHideBehindAnEmptySelectedList() {
        assertThrows(ResultApplicationService.Rejected.class,()->ResultApplicationService.providerEnvelope(input(),payload(true,"[]")));
    }
    @Test void unselectedOldHighCannotHideBehindAValidNewSelection() {
        assertThrows(ResultApplicationService.Rejected.class,()->ResultApplicationService.providerEnvelope(input(),payload(true,"[\"fictional-new\"]")));
    }
    @Test void eligibleHighCanRemainUnselectedWhileAllOldJudgmentsArePreserved() {
        String raw=ResultApplicationService.providerEnvelope(input(),payload(false,"[]"));
        var result=ResultApplicationService.validate(input(),raw);assertEquals("value",result.kind());
        assertTrue(result.payloadJson().contains("Fictional old release"));
        assertDoesNotThrow(()->ResultApplicationService.providerEnvelope(input(),payload(false,"[\"fictional-new\"]")));
    }
    @Test void selectedOldObjectStillFailsWhetherMarkedHighOrLow() {
        for(boolean high:List.of(true,false))assertThrows(ResultApplicationService.Rejected.class,
                ()->ResultApplicationService.providerEnvelope(input(),payload(high,"[\"fictional-old\"]")));
    }
}
