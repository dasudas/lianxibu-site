package com.lianxibu.site.controller.collector;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrozenObservationBatchTest {
    @Test void preservesExactObjectBytesAcrossNestedStringsAndWhitespace() {
        String first = " { \"id\" : 1, \"payload\" : {\"text\":\"escaped \\\" } [ \\\"\"} }";
        String second = "{\"id\":2,\"payload\":[{\"x\":\"[]{}\"}]}";
        String raw = "{ \"observations\" : [" + first + "," + second + "] }";
        var objects = FrozenObservationBatch.extract(raw.getBytes(StandardCharsets.UTF_8), CanonicalJson.parse(raw));
        assertEquals(2, objects.size());
        assertEquals(first, new String(objects.get(0), StandardCharsets.UTF_8));
        assertEquals(second, new String(objects.get(1), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> FrozenObservationBatch.extract(
                "{\"observations\":[{}]}".getBytes(StandardCharsets.UTF_8), CanonicalJson.parse(raw)));
    }
}
