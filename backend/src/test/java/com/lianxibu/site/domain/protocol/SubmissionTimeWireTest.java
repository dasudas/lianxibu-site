package com.lianxibu.site.domain.protocol;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.service.articles.ObservationPayloadPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SubmissionTimeWireTest {
    @Test void validatesDualVersionsAndTimeCombinationsWithoutChangingHashes() throws Exception {
        var samples = CanonicalJson.parse(Files.readAllBytes(Path.of("..", "contracts", "hn-time-wire-examples.json"))).get("cases");
        int accepted=0, rejected=0;
        for(var sample:samples) {
            Runnable check=()-> {var observation=ObservationEnvelope.fromJson(sample.get("value"));
                ObservationPayloadPolicy.validate(observation);
                assertEquals(sample.get("value").get("payload_hash").asText(),observation.payloadHash());};
            if(sample.get("valid").booleanValue()){check.run();accepted++;}
            else {assertThrows(IllegalArgumentException.class,check::run,sample.get("name").asText());rejected++;}
        }
        assertEquals(3,accepted);assertEquals(7,rejected);
    }
}
