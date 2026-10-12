package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.Test;

class ValueFlagFactoryTest extends ValueResultFactoryTest {
    @Override ValueClassificationConsumerTest consumerFixture(){return ValueFlagConsumerTest.consumer(fixture());}
    @Test void actualPermitFactoryKeepsOriginalV2OrdinaryValueAndExactRequest() {
        assertEquals("2",factory.forAttempt(identity).input().valueResultVersion());
        var result=apply();assertEquals(ResultApplicationService.Status.APPLIED,result.status());var n=CanonicalJson.parse(f.f.jdbc.queryForObject("SELECT value_json FROM source_run_items WHERE id=?",String.class,base.base.item));assertEquals("member-value-v3",n.get("schema_version").asText());assertTrue(n.get("valuable").booleanValue());assertFalse(n.get("high_value").booleanValue());assertEquals(1,sends);assertEquals(result,apply());assertEquals(1,sends);
    }
}
