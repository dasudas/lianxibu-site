package com.lianxibu.site.domain.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WireValuesTest {
    @Test
    void validatesCurrentObservationWireExamples() throws Exception {
        Path samples = Path.of(System.getProperty("user.dir"), "..", "contracts", "wire-examples.json");
        var cases = JsonMapper.shared().readTree(Files.readString(samples)).get("cases");
        int valid = 0;
        int invalid = 0;
        for (var sample : cases) {
            if (!"ObservationEnvelope".equals(sample.get("schema").textValue())) continue;
            if (sample.get("valid").booleanValue()) {
                ObservationEnvelope.fromJson(sample.get("value"));
                valid++;
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> ObservationEnvelope.fromJson(sample.get("value")));
                invalid++;
            }
        }
        assertEquals(3, valid);
        assertEquals(1, invalid);
    }

    @Test
    void validatesCurrentManifestControlWireExamples() throws Exception {
        Path samples = Path.of(System.getProperty("user.dir"), "..", "contracts", "wire-examples.json");
        var cases = JsonMapper.shared().readTree(Files.readString(samples)).get("cases");
        int valid = 0;
        int invalid = 0;
        for (var sample : cases) {
            if (!"ManifestControl".equals(sample.get("schema").textValue())) continue;
            var path = sample.get("path_params");
            String pathRunId = path.isNull() ? "fixture" : path.get("run_id").textValue();
            if (sample.get("valid").booleanValue()) {
                var control = ManifestControl.fromJson(sample.get("value"), pathRunId);
                assertEquals("51cc014d66d0354c5eb5f5402d9d700023c6ef9976bc3ea439bfed5fac580db2",
                        control.partHash());
                assertEquals("7d3402ad1d2d76cea474094a85196222a005be440a7cfc0b613b21c1672e8676",
                        control.businessHash());
                assertEquals("10b99848bac33e51f06b65220b1572e9a3af9c5d16aa0955a9dda9ad24e01dd9",
                        control.attemptHash("node-demo"));
                valid++;
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> ManifestControl.fromJson(sample.get("value"), pathRunId));
                invalid++;
            }
        }
        assertEquals(1, valid);
        assertEquals(2, invalid);
    }

    @Test
    void rejectsUnknownVersionMissingNullableAndWrongPathIdentity() {
        String base = "{\"kind\":\"content_version\",\"content_kind\":\"report\","
                + "\"content_id\":\"demo\",\"revision\":1,\"body_hash\":\""
                + "0".repeat(64) + "\",\"input_job_id\":null}";
        var key = ContentVersionKey.fromJson(CanonicalJson.parse(base));
        key.requirePathIdentity("report", "demo");
        assertThrows(IllegalArgumentException.class, () -> key.requirePathIdentity("skill", "demo"));
        assertThrows(IllegalArgumentException.class,
                () -> ContentVersionKey.fromJson(CanonicalJson.parse(base.replace("\"input_job_id\":null", "\"extra\":1"))));
        assertThrows(IllegalArgumentException.class,
                () -> ContentVersionKey.fromJson(CanonicalJson.parse(base.replace("content_version", "unknown"))));
        assertThrows(IllegalArgumentException.class,
                () -> ContentVersionKey.fromJson(CanonicalJson.parse(base.replace("\"revision\":1", "\"revision\":1.5"))));
    }

    @Test
    void boundsIdsAndTimePrecisely() {
        assertThrows(IllegalArgumentException.class,
                () -> WireValues.id(CanonicalJson.parse("\"" + "中".repeat(81) + "\""), true));
        assertThrows(IllegalArgumentException.class,
                () -> WireValues.id(Character.toString((char) 0xd800), false));
        assertThrows(IllegalArgumentException.class,
                () -> WireValues.utcMicroseconds(CanonicalJson.parse("\"2026-09-29T01:02:03Z\"")));
        assertEquals(9_007_199_254_740_991L,
                WireValues.nonnegativeInteger(CanonicalJson.parse("9007199254740991")));
    }

    @Test
    void rejectsUnknownPayloadMemberAndUnsupportedCatalogVersion() throws Exception {
        Path samples = Path.of(System.getProperty("user.dir"), "..", "contracts", "wire-examples.json");
        var cases = JsonMapper.shared().readTree(Files.readString(samples)).get("cases");
        for (var sample : cases) {
            if (!"ObservationEnvelope".equals(sample.get("schema").textValue()) ||
                    !sample.get("valid").booleanValue()) continue;
            ObjectNode changed = ((ObjectNode) sample.get("value")).deepCopy();
            ObjectNode payload = (ObjectNode) changed.get("payload");
            payload.put("unexpected", true);
            assertThrows(IllegalArgumentException.class, () -> ObservationEnvelope.fromJson(changed));
            if ("catalog".equals(changed.get("kind").textValue())) {
                payload.remove("unexpected");
                payload.put("schema_version", "2");
                assertThrows(IllegalArgumentException.class, () -> ObservationEnvelope.fromJson(changed));
            }
        }
    }
}
