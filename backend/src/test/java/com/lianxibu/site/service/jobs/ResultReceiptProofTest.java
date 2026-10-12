package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ResultReceiptProofTest extends ManualResultRefusalTest {
    ObjectNode receipt(String key){return (ObjectNode)CanonicalJson.parse(f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE action_key_hash=UNHEX(?)",String.class,JdbcResultState.sha(key)));}
    void save(String key,ObjectNode body){String value=JdbcFrozenInputs.canonical(body);f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=UNHEX(?) WHERE action_key_hash=UNHEX(?)",value,JdbcResultState.sha(value),JdbcResultState.sha(key));}
    @Test void damagedSchemaCannotMasqueradeAsIdempotencyConflictEvenWithMatchingNativeHash(){long before=revision();apply("fictional-receipt-schema",before,1L);var body=receipt("fictional-receipt-schema");body.put("schema_version","fictional-broken-schema");body.put("action_hash","0".repeat(64));save("fictional-receipt-schema",body);var failure=assertThrows(RuntimeException.class,()->apply("fictional-receipt-schema",before,1L));assertFalse(failure instanceof ManualRejected);assertEquals(1,f.handlerCalls);}
    @Test void originalContentVersionProofCannotBeIgnoredDuringSuccessfulReplyReplay(){long before=revision();apply("fictional-receipt-version",before,1L);var original=receipt("fictional-receipt-version");var body=original.deepCopy();((ObjectNode)body.get("content_version")).put("body_hash","0".repeat(64));save("fictional-receipt-version",body);assertThrows(RuntimeException.class,()->apply("fictional-receipt-version",before,1L));
        body=original.deepCopy();((ObjectNode)body.get("content_version")).put("revision",0);save("fictional-receipt-version",body);assertThrows(RuntimeException.class,()->apply("fictional-receipt-version",before,1L));
        body=original.deepCopy();body.put("content_revision",0);save("fictional-receipt-version",body);assertThrows(RuntimeException.class,()->apply("fictional-receipt-version",before,1L));
        save("fictional-receipt-version",original);assertEquals(Status.APPLIED,apply("fictional-receipt-version",before,1L).status());assertEquals(1,f.handlerCalls);
    }
}
