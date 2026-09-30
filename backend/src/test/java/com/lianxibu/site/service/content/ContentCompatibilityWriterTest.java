package com.lianxibu.site.service.content;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianxibu.site.repository.content.JdbcContentWrites;
import com.lianxibu.site.service.content.ContentCompatibilityWriter.ContentMappingRequired;
import com.lianxibu.site.service.content.ContentCompatibilityWriter.Input;
import com.lianxibu.site.service.content.ContentCompatibilityWriter.ReviewedLegacyContent;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ContentCompatibilityWriterTest {
    private static final String PERIOD = "{\"content_kind\":\"report\",\"identity\":{"
            + "\"type\":\"period\",\"timezone\":\"Asia/Shanghai\","
            + "\"period_start\":\"2026-01-01T00:00:00Z\","
            + "\"period_end\":\"2026-01-02T00:00:00Z\"}}";
    private JdbcTemplate jdbc;
    private JdbcContentWrites store;
    private DataSourceTransactionManager tx;
    private final Instant now = Instant.parse("2026-01-03T00:00:00Z");
    private List<ContentCompatibilityWriter.ConversionReceipt> journal;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:content-write-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id VARCHAR(64) PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock(id) VALUES(?)", "only-slot");
        jdbc.execute("CREATE TABLE contents(kind VARCHAR(16) NOT NULL,id VARCHAR(64) NOT NULL,"
                + "natural_key VARCHAR(255) NOT NULL,source_id VARCHAR(80),title VARCHAR(512) NOT NULL,"
                + "body_json CLOB NOT NULL,state VARCHAR(16) NOT NULL,review_hash BINARY(32),"
                + "revision BIGINT NOT NULL,published_at TIMESTAMP,updated_at TIMESTAMP NOT NULL,"
                + "entity_id VARCHAR(64),input_job_id VARCHAR(64),period_key_hash BINARY(32),"
                + "body_revision BIGINT,body_hash_v1 BINARY(32),content_key_json CLOB,"
                + "PRIMARY KEY(kind,id),UNIQUE(kind,natural_key),UNIQUE(period_key_hash))");
        store = new JdbcContentWrites(source);
        tx = new DataSourceTransactionManager(source);
        journal = new ArrayList<>();
    }

    @Test void reviewedLegacyDraftKeepsNaturalKeyAndBodyAndRecordsBaseline() {
        insertOld("report", "shared-id", "legacy:period", "Old title", "{\"title\":\"Old title\"}");
        insertOld("agent", "shared-id", "legacy:agent", "Agent", "{\"title\":\"Agent\"}");
        var old = store.current("report", "shared-id");
        var writer = writer(List.of(review(old)), journal::add);
        var result = writer.accept(input());
        assertEquals("shared-id", result.id());
        assertEquals("legacy:period", result.naturalKey());
        assertEquals(3L, result.bodyRevision());
        assertTrue(result.legacyConverted());
        var converted = store.current("report", "shared-id");
        assertEquals(old.bodyJson(), converted.bodyJson());
        assertEquals(old.title(), converted.title());
        assertEquals(old.revision(), converted.revision());
        assertArrayEquals(old.oldReviewHash(), converted.oldReviewHash());
        assertEquals(3L, converted.bodyRevision());
        assertNotNull(converted.bodyHashV1());
        assertNotNull(converted.periodKeyHash());
        assertNull(store.current("agent", "shared-id").bodyRevision());
        assertEquals(1, journal.size());
        assertEquals("legacy:period", journal.getFirst().naturalKey());
        var repeated = writer.accept(input());
        assertEquals(result.id(), repeated.id());
        assertFalse(repeated.legacyConverted());
        assertEquals(1, journal.size());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM contents", Long.class));
    }

    @Test void firstNewInsertFillsLegacyRequiredColumnsAndReusesSameKey() {
        var writer = writer(List.of(), journal::add);
        var result = writer.accept(input());
        assertTrue(result.inserted());
        assertEquals(67, result.naturalKey().length());
        var row = store.current("report", result.id());
        assertEquals("draft", row.state());
        assertEquals(1L, row.revision());
        assertEquals(1L, row.bodyRevision());
        assertNotNull(row.bodyHashV1());
        assertNotNull(row.contentKeyJson());
        assertEquals("job-fixture", row.inputJobId());
        assertNull(row.oldReviewHash());
        assertEquals(result.id(), writer.accept(input()).id());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM contents", Long.class));
    }

    @Test void unknownOldIdentityAndJournalFailureBlockWithoutPartialConversion() {
        insertOld("report", "old", "legacy:unknown", "Old", "{\"title\":\"Old\"}");
        assertThrows(ContentMappingRequired.class, () -> writer(List.of(), journal::add).accept(input()));
        var old = store.current("report", "old");
        var writer = writer(List.of(review(old)), receipt -> { throw new IllegalStateException("journal unavailable"); });
        assertThrows(IllegalStateException.class, () -> writer.accept(input()));
        assertNull(store.current("report", "old").contentKeyJson());
        assertNull(store.current("report", "old").bodyRevision());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM contents", Long.class));
    }

    @Test void snapshotProofAndTitleMismatchFailClosed() {
        var denied = new ContentCompatibilityWriter(store, tx, Clock.fixed(now, ZoneOffset.UTC),
                (job, key) -> false, journal::add, List.of());
        assertThrows(ContentMappingRequired.class, () -> denied.accept(input()));
        assertThrows(IllegalArgumentException.class, () -> writer(List.of(), journal::add)
                .accept(new Input("report", PERIOD, "Wrong", "{\"title\":\"Other\"}", "job-fixture")));
    }

    private ContentCompatibilityWriter writer(List<ReviewedLegacyContent> mappings,
                                               ContentCompatibilityWriter.ConversionJournal target) {
        return new ContentCompatibilityWriter(store, tx, Clock.fixed(now, ZoneOffset.UTC),
                (job, key) -> "job-fixture".equals(job), target, mappings);
    }
    private static Input input() {
        return new Input("report", PERIOD, "New title", "{\"title\":\"New title\"}", "job-fixture");
    }
    private static ReviewedLegacyContent review(JdbcContentWrites.Row row) {
        return new ReviewedLegacyContent(row.kind(), row.id(), row.naturalKey(), PERIOD,
                ContentCompatibilityWriter.snapshotSha256(row));
    }
    private void insertOld(String kind, String id, String naturalKey, String title, String body) {
        byte[] review = new byte[32];
        review[0] = 7;
        jdbc.update("INSERT INTO contents(kind,id,natural_key,title,body_json,state,review_hash,"
                        + "revision,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                kind, id, naturalKey, title, body, "draft", review, 3, Timestamp.from(now));
    }
}
