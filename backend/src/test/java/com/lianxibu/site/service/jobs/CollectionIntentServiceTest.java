package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.service.sources.CollectionHandoffService.Facts;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollectionIntentServiceTest {
    GenerationJdbcStoreTest fixture;
    GenerationJdbcStoreTest fixture() { return new GenerationJdbcStoreTest(); }
    @BeforeEach void setup() { fixture = fixture(); fixture.fixture(); }
    Facts facts(String category, String state, long accepted, long excluded) {
        return new Facts("fictional-run", "fictional-source", "daily", LocalDate.of(2026, 1, 1),
                1, category, "production", 1, "a".repeat(64), "b".repeat(64),
                state, "succeeded", accepted + excluded, accepted, excluded);
    }
    CollectionIntentService service() { return new CollectionIntentService(fixture.source, fixture.clock); }
    String save(Facts f) {
        return fixture.writes.execute(new SharedWriteExecutor.ActionContext("fictional-write", "fictional-id", null),
                k -> null, a -> service().ensure(f));
    }
    @Test void durableReplayKeepsOriginalIntentAndNeverCreatesCallBudget() {
        var f = facts("news_tech", "complete", 2, 0);
        assertThrows(IllegalStateException.class, () -> service().ensure(f));
        String id = save(f);
        assertEquals(id, save(f));
        assertEquals(1, fixture.count("record_kind='generation_intent' AND state='waiting_input' AND task_type='news_dedup'"));
        assertEquals(1, fixture.count("target_type='collection_handoff'"));
        assertEquals(0, fixture.count("record_kind IN ('logical_task','ai_attempt')"));
        fixture.jdbc.update("UPDATE jobs SET state='queued',revision=3 WHERE id=?", id);
        assertEquals(id, save(f));
        assertEquals("queued", fixture.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?", String.class,id));
        fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='collection_handoff'", new byte[32]);
        assertThrows(RuntimeException.class, () -> save(f));
    }
    @Test void emptyFailureAndAllCategoriesRemainDistinctPendingIntentions() {
        for (String category : List.of("news_tech", "agent", "skill", "workflow", "model")) {
            fixture = fixture(); fixture.fixture();
            save(facts(category, "empty_success", 0, 0));
            assertEquals((category.equals("news_tech") ? "news" : category) + "_dedup",
                    fixture.jdbc.queryForObject("SELECT task_type FROM jobs WHERE record_kind='generation_intent'", String.class));
            assertThrows(RuntimeException.class, () -> save(facts(category, "no_usable_input", 0, 0)));
        }
    }
    @Test void receiptFailureRollsBackIntentAndChangedNaturalRunCannotAlias() {
        fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_receipt_failure CHECK(record_kind<>'disposition')");
        assertThrows(RuntimeException.class, () -> save(facts("agent", "partial", 1, 1)));
        assertEquals(0, fixture.count("1=1"));
        fixture.dropReceiptConstraint();
        var f=facts("agent", "partial", 1, 1); save(f);
        var changed=new Facts("fictional-other-run", f.sourceId(),f.runKind(),f.businessDate(),f.generation(),
                f.category(),f.activationStage(),f.configVersion(),f.configHash(),f.manifestHash(),
                f.deliveryState(),f.fetchState(),f.expected(),f.accepted(),f.excluded());
        assertThrows(RuntimeException.class,()->save(changed));
        assertEquals(2,fixture.count("1=1"));
    }
}
