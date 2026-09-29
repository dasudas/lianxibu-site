package com.lianxibu.site.domain.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test
    void historicalKeyOrderAndNullVector() {
        var value = CanonicalJson.parse("{\"b\":1,\"a\":null}");
        assertEquals("7b2261223a6e756c6c2c2262223a317d",
                HexFormat.of().formatHex(CanonicalJson.bytes(value)));
        assertEquals("441b275ad8b530cd797b599987bc509603b2fb64ecc7422e658046651de5d56d",
                CanonicalJson.hash(CanonicalJson.Domain.CONTENT, value));
    }

    @Test
    void unicodeControlsAndScalarOrderVectors() {
        String escapedNul = "\\" + "u0000";
        String raw = "{\"title\":\"中文😀\",\"text\":\"a\\r\\nb\\t/"
                + Character.toString(0x2028) + "\",\"control\":\"" + escapedNul + "\"}";
        var value = CanonicalJson.parse(raw);
        assertEquals("80d4a7aa5c0acd6cc74fdcd62b325ba003d12d79ba6d9dd94d480b7ae0edc66a",
                CanonicalJson.hash(CanonicalJson.Domain.CONTENT, value));
        String supplemental = new String(Character.toChars(0x10000));
        String privateUse = Character.toString(0xe000);
        var keys = CanonicalJson.parse("{\"" + supplemental + "\":1,\"" + privateUse + "\":2}");
        assertEquals("aecd3c70a1eeb1de896fb043db84c1c21c69e9c811e854abcc4cb6c278fda24a",
                CanonicalJson.hash(CanonicalJson.Domain.CONTENT, keys));
    }

    @Test
    void emptyManifestAndArrayOrderVectors() {
        assertEquals("6a1c37cdf9fdaa9e55d661c45a4b582e5f39a61784498cba25ff3d8c7b410f9f",
                CanonicalJson.hash(CanonicalJson.Domain.MANIFEST,
                        CanonicalJson.parse("{\"run_id\":\"run-001\",\"expected_count\":0,\"parts\":[]}")));
        assertEquals("f38af94e44f3accde5a36667e401e0d14e0485fd152d3d0f847f3ecec1a47632",
                CanonicalJson.hash(CanonicalJson.Domain.CONTENT,
                        CanonicalJson.parse("{\"items\":[1,2]}")));
        assertEquals("4f791413388bed70fe5d3097c184e24fded1e56147fdc6e264d994bd8fc5b682",
                CanonicalJson.hash(CanonicalJson.Domain.CONTENT,
                        CanonicalJson.parse("{\"items\":[2,1]}")));
        assertNotEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,
                        CanonicalJson.parse("{\"items\":[1,2]}")),
                CanonicalJson.hash(CanonicalJson.Domain.CONTENT,
                        CanonicalJson.parse("{\"items\":[2,1]}")));
    }

    @Test
    void historicalReviewAndSafeIntegerVectors() {
        String review = "{\"body_hash\":\"" + "0".repeat(64) + "\","
                + "\"input_job_id\":\"intent-001\",\"input_hash\":\"" + "1".repeat(64)
                + "\",\"dependencies\":[]}";
        assertEquals("af4207ae9d1031dcc8db21d6a58201a2e3b00b26d2d5bca5dfdd8d9299571c61",
                CanonicalJson.hash(CanonicalJson.Domain.REVIEW, CanonicalJson.parse(review)));
        assertEquals("6521cd39f4e72ca61337b9379d4cc68508ae8825635f450f8bf10b5e75b9e463",
                CanonicalJson.hash(CanonicalJson.Domain.CONTENT,
                        CanonicalJson.parse("{\"score\":\"1.20\",\"count\":9007199254740991,\"ok\":true}")));
    }

    @Test
    void historicalObservationAndManifestChainVectors() {
        String observation = """
                {"protocol_revision":"0.10","observation_id":"obs-001","member_id":"member-001",
                 "run_id":"run-001","business_date":"2026-09-26","source_id":"source-example",
                 "source_revision":1,"source_item_id":"item-001","kind":"article",
                 "retrieved_at":"2026-09-27T00:00:00.000000Z","replaces_observation_id":null,
                 "payload":{"title":"示例","source_url":"https://example.com/item",
                 "published_at":null,"updated_at":null,"time_basis":"unknown","source_summary":null,
                 "body_text":"text\\n","content_status":"complete","body_truncated":false,
                 "parser_version":"1","config_version":1,
                 "source_order":{"basis":"unknown","domain":null,"value":null,"evidence_ref":null}}}
                """;
        assertEquals("104eed018164444d811132953885cc0881e03d4757e374aa31d7248b8009cc45",
                CanonicalJson.hash(CanonicalJson.Domain.OBSERVATION, CanonicalJson.parse(observation)));
        String partHash = "2437a1d6b087ddcbc41ecaadb003f2f5c5237b6390a46420a4ea702caf3625f9";
        String part = "{\"run_id\":\"run-001\",\"part_no\":0,\"members\":[{"
                + "\"member_id\":\"member-001\",\"source_item_id\":\"item-001\","
                + "\"observation_id\":\"obs-001\",\"payload_hash\":\""
                + "104eed018164444d811132953885cc0881e03d4757e374aa31d7248b8009cc45"
                + "\",\"replaces_observation_id\":null}]}";
        assertEquals(partHash, CanonicalJson.hash(CanonicalJson.Domain.MANIFEST_PART,
                CanonicalJson.parse(part)));
        String manifest = "{\"run_id\":\"run-001\",\"expected_count\":1,\"parts\":[{"
                + "\"part_no\":0,\"part_hash\":\"" + partHash + "\",\"member_count\":1}]}";
        assertEquals("d6b618be9d830b38b3f6cfbfb611314bcaed533cbcad6b354d245309b0d1a230",
                CanonicalJson.hash(CanonicalJson.Domain.MANIFEST, CanonicalJson.parse(manifest)));
    }

    @Test
    void rejectsMalformedAndNonCanonicalWireNumbers() {
        for (String raw : new String[] {
                "{\"x\":1,\"x\":2}", "{\"x\":1.0}", "{\"x\":1e0}",
                "{\"x\":-0}", "{\"x\":9007199254740992}",
                "{\"x\":\"" + "\\" + "uD800\"}",
                "{}{}", "\ufeff{}"}) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse(raw), raw);
        }
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalJson.parse(new byte[] {(byte) 0xc3, (byte) 0x28}));
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8),
                CanonicalJson.bytes(CanonicalJson.parse("{}")));
    }
}
