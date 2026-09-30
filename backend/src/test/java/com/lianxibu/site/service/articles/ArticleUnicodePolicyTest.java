package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.ObservationEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ArticleUnicodePolicyTest {
    @Test void supplementaryCharactersUseSchemaCodePointLimits() throws Exception {
        String symbol = new String(Character.toChars(0x1F600));
        var cases = CanonicalJson.parse(Files.readAllBytes(Path.of("../contracts/hn-time-wire-examples.json"))).get("cases");
        ObjectNode wire = null;
        for (var sample : cases) if (sample.get("valid").booleanValue()) {
            wire = ((ObjectNode) sample.get("value")).deepCopy();
            break;
        }
        assertNotNull(wire);
        ObjectNode payload = (ObjectNode) wire.get("payload");
        payload.put("title", symbol.repeat(512));
        payload.putNull("source_summary");
        payload.put("body_text", symbol.repeat(50000));
        validate(wire);
        payload.put("body_text", symbol.repeat(50001));
        ObjectNode bodyInvalid = wire.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> validate(bodyInvalid));
        payload.putNull("body_text");
        payload.put("source_summary", symbol.repeat(16384));
        validate(wire);
        payload.put("source_summary", symbol.repeat(16385));
        ObjectNode summaryInvalid = wire.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> validate(summaryInvalid));
        payload.putNull("source_summary");
        payload.put("title", symbol.repeat(513));
        ObjectNode titleInvalid = wire.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> validate(titleInvalid));
    }

    private static void validate(ObjectNode wire) {
        wire.remove("payload_hash");
        wire.put("payload_hash", CanonicalJson.hash(CanonicalJson.Domain.OBSERVATION, wire));
        ObservationPayloadPolicy.validate(ObservationEnvelope.fromJson(wire));
    }
}
