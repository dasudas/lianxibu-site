package com.lianxibu.site.service.content;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.content.JdbcContentReads;
import java.sql.Timestamp;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ContentReadServiceTest {
    private JdbcTemplate jdbc;
    private ContentReadService reads;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:contents-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE contents(kind VARCHAR(16), id VARCHAR(64), natural_key VARCHAR(255),"
                + " title VARCHAR(512), body_json CLOB, state VARCHAR(16), revision BIGINT,"
                + " body_revision BIGINT, published_at TIMESTAMP, input_job_id VARCHAR(64),"
                + " body_hash_v1 BINARY(32), review_hash_v1 BINARY(32),"
                + " review_dependency_stamp CLOB, review_stamp_hash BINARY(32),"
                + " review_stamp_id VARCHAR(64), reviewed_input_hash BINARY(32),"
                + " blocking_summary_json CLOB, PRIMARY KEY(kind,id))");
        reads = new ContentReadService(new JdbcContentReads(source),
                new DataSourceTransactionManager(source), row -> true);
    }

    @Test void legacyDraftKeepsBodyAndCompositeKeyButNeverBecomesReviewed() {
        String original = "{\"overview\":\"fictional source \\\"A\\\"\",\"item_ids_raw\":\"x,y\"}";
        insert("report", "same", original, "draft", false);
        insert("agent", "same", "{\"name\":\"Fixture\"}", "draft", false);
        var report = reads.adminCurrent("report", "same").orElseThrow();
        assertEquals(original, report.bodyJson());
        assertEquals("report", report.kind());
        assertEquals("agent", reads.adminCurrent("agent", "same").orElseThrow().kind());
        assertNull(report.bodyRevision());
        assertEquals(3, report.effectiveBodyRevision());
        assertTrue(reads.published("report", "same").isEmpty());
        assertEquals(0, reads.publishedPage(null, 1, 10).total());
        assertTrue(reads.adminRevision("report", "same", 2).isEmpty());
        assertEquals(original, reads.adminRevision("report", "same", 3).orElseThrow().bodyJson());
    }

    @Test void unknownValidityAndIncompleteProofFailClosedEvenWithPublishedState() {
        insert("report", "unknown", "{}", "published", false);
        insert("report", "proven", "{}", "published", true);
        assertTrue(reads.published("report", "unknown").isEmpty());
        assertEquals(1, reads.publishedPage("report", 1, 1).total());
        assertEquals("proven", reads.publishedPage("report", 1, 1).items().getFirst().id());
        assertEquals(0, reads.publishedPage("agent", 1, 1).total());
        var denied = new ContentReadService(new JdbcContentReads(jdbc.getDataSource()),
                new DataSourceTransactionManager(jdbc.getDataSource()),
                ContentReadService.denyUnverifiedReferences());
        assertEquals(0, denied.publishedPage("report", 1, 1).total());
    }

    private void insert(String kind, String id, String body, String state, boolean proof) {
        byte[] hash = proof ? new byte[32] : null;
        jdbc.update("INSERT INTO contents(kind,id,natural_key,title,body_json,state,revision,"
                        + "body_revision,published_at,input_job_id,body_hash_v1,review_hash_v1,"
                        + "review_dependency_stamp,review_stamp_hash,review_stamp_id,reviewed_input_hash)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", kind, id, "legacy-" + id,
                "Fixture", body, state, 3L, proof ? 3L : null,
                "published".equals(state) ? Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")) : null,
                proof ? "fixture-job" : null, hash, hash, proof ? "[]" : null,
                hash, proof ? "fixture-stamp" : null, hash);
    }
}
