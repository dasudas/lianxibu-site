package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.OperationalNotification.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OperationalNotificationRendererTest {
    static OperationalNoticeDelivery.Event event(String action,String component,String resource) {
        return new OperationalNoticeDelivery.Event("opsn_"+"a".repeat(48),"OPS-04",component,resource,"L1",action,Instant.parse("2026-10-01T00:00:00Z"),"4","5");
    }
    @Test void rendersOnlyOriginalNoticeFieldsAndClosedMailPayload() {
        for(String action:java.util.List.of("trigger","upgrade","reminder","resolved")) {
            var e=event(action,"fictional-db","rm-fictional");
            var payload=OperationalNotificationRenderer.render(e);
            var json=CanonicalJson.parse(payload.json());
            assertEquals(3,json.size());assertEquals("OPS-04",json.get("ruleName").asString());
            assertTrue(payload.message().contains("event_id="+e.id()));
            assertTrue(payload.message().contains("action="+action));
            assertTrue(payload.title().contains("L1"));assertTrue(payload.message().contains("2026-10-01T00:00:00Z"));
            assertFalse(payload.toString().contains("rm-fictional"));
        }
        assertEquals(Destination.LOG,Destination.valueOf("LOG"));assertEquals(Destination.CLOUD,Destination.valueOf("CLOUD"));
    }
    @Test void unsafeTextActionAndOversizeFailBeforeSending() {
        assertThrows(IllegalArgumentException.class,()->OperationalNotificationRenderer.render(event("arbitrary","fictional-db","rm-fictional")));
        assertThrows(IllegalArgumentException.class,()->OperationalNotificationRenderer.render(event("trigger","fictional\ncomponent","rm-fictional")));
        assertThrows(IllegalArgumentException.class,()->OperationalNotificationRenderer.render(event("trigger","fictional-db","rm-fictional\u0000")));
        assertThrows(IllegalArgumentException.class,()->new Payload("OPS-04","x","a".repeat(8192)));
        assertThrows(IllegalArgumentException.class,()->new Payload("OPS-04","x","\uD800"));
        assertThrows(IllegalArgumentException.class,()->new Receipt("arbitrary-provider-message"));
        assertEquals("11111111-1111-4111-8111-111111111111",new Receipt("11111111-1111-4111-8111-111111111111").requestId());
    }
}
