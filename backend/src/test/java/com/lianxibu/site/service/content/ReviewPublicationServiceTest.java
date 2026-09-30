package com.lianxibu.site.service.content;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.repository.content.JdbcContentReads.Row;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.content.ReviewPublicationService.Current;
import com.lianxibu.site.service.content.ReviewPublicationService.Dependency;
import com.lianxibu.site.service.content.ReviewPublicationService.Frozen;
import com.lianxibu.site.service.content.ReviewPublicationService.Receipt;
import com.lianxibu.site.service.content.ReviewPublicationService.Stamp;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;

class ReviewPublicationServiceTest {
    private static final JsonNode EVIDENCE = CanonicalJson.parse("{\"kind\":\"article_revision\",\"id\":\"e-1\"}");
    private static final JsonNode INTERMEDIATE = CanonicalJson.parse("{\"kind\":\"content_version\",\"id\":\"c-1\"}");
    private static final byte[] INPUT_HASH = new byte[32];
    private JdbcTemplate jdbc;
    private Fixture store;
    private ReviewPublicationService service;
    private ContentReadService reads;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:review-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE contents(kind VARCHAR(16),id VARCHAR(64),natural_key VARCHAR(255),"
                + "title VARCHAR(512),body_json CLOB,state VARCHAR(16),revision BIGINT,"
                + "body_revision BIGINT,published_at TIMESTAMP,input_job_id VARCHAR(64),"
                + "body_hash_v1 BINARY(32),review_hash_v1 BINARY(32),"
                + "review_dependency_stamp CLOB,review_stamp_hash BINARY(32),"
                + "review_stamp_id VARCHAR(64),reviewed_input_hash BINARY(32),"
                + "blocking_summary_json CLOB,PRIMARY KEY(kind,id))");
        jdbc.execute("CREATE TABLE receipts(action_key VARCHAR(128) PRIMARY KEY,kind VARCHAR(16),"
                + "content_id VARCHAR(64),revision BIGINT,state VARCHAR(16),stamp_hash VARCHAR(64))");
        byte[] bodyHash = bodyHash("{\"title\":\"Fixture\"}");
        jdbc.update("INSERT INTO contents(kind,id,natural_key,title,body_json,state,revision,"
                + "body_revision,input_job_id,body_hash_v1) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "report", "r-1", "fixture", "Fixture", "{\"title\":\"Fixture\"}",
                "draft", 1L, 1L, "job-1", bodyHash);
        store = new Fixture(source);
        service = new ReviewPublicationService(new SharedWriteExecutor(
                new JdbcSharedWriteEntry(source), new DataSourceTransactionManager(source)), store);
        reads = new ContentReadService(new JdbcContentReads(source),
                new DataSourceTransactionManager(source), service);
    }

    @Test void approvePublishAndPublicReadsBindFullVector() {
        assertEquals("approved", service.approve("report", "r-1", 1, action("a")).state());
        assertEquals("published", service.publish("report", "r-1", 2, action("p")).state());
        assertTrue(reads.published("report", "r-1").isPresent());
        assertEquals(1, reads.publishedPage("report", 1, 20).total());
        store.evidenceRevision = 1;
        store.evidenceValid = false;
        assertTrue(reads.published("report", "r-1").isEmpty());
        assertEquals(0, reads.publishedPage("report", 1, 20).total());
        store.evidenceRevision = 2;
        store.evidenceValid = true;
        assertTrue(reads.published("report", "r-1").isEmpty());
        assertThrows(ReviewPublicationService.Conflict.class,
                () -> service.publish("report", "r-1", 3, action("p2")));
        assertEquals("approved", service.approve("report", "r-1", 3, action("a2")).state());
        service.publish("report", "r-1", 4, action("p3"));
        assertTrue(reads.published("report", "r-1").isPresent());
    }

    @Test void intermediateVersionChangeAndMissingReferenceRejectImmediately() {
        service.approve("report", "r-1", 1, action("a"));
        store.intermediateRevision = 1;
        assertThrows(ReviewPublicationService.Conflict.class,
                () -> service.publish("report", "r-1", 2, action("p")));
        store.intermediateRevision = 0;
        service.publish("report", "r-1", 2, action("p2"));
        store.complete = false;
        assertTrue(reads.published("report", "r-1").isEmpty());
        store.complete = true;
        store.evidenceValid = false;
        assertThrows(ReviewPublicationService.Conflict.class,
                () -> service.approve("report", "r-1", 3, action("a2")));
    }

    @Test void programCoverageUsesExplicitEmptyVectorAndEditReturnsDraft() {
        store.targets = List.of();
        Stamp stamp = ReviewPublicationService.stamp(store.snapshot(store.row()));
        assertEquals("[]", stamp.json());
        service.approve("report", "r-1", 1, action("a"));
        service.publish("report", "r-1", 2, action("p"));
        assertTrue(reads.published("report", "r-1").isPresent());
        service.edit("report", "r-1", 3, "{\"title\":\"Updated\"}", action("e"));
        assertTrue(reads.published("report", "r-1").isEmpty());
        assertEquals("draft", reads.adminCurrent("report", "r-1").orElseThrow().state());
        assertEquals(2, reads.adminCurrent("report", "r-1").orElseThrow().bodyRevision());
    }

    @Test void forgedBodyAndSnapshotBindingFailClosed() {
        service.approve("report", "r-1", 1, action("a"));
        service.publish("report", "r-1", 2, action("p"));
        jdbc.update("UPDATE contents SET body_json=? WHERE kind=? AND id=?",
                "{\"title\":\"Forged\"}", "report", "r-1");
        assertTrue(reads.published("report", "r-1").isEmpty());
        jdbc.update("UPDATE contents SET body_json=? WHERE kind=? AND id=?",
                "{\"title\":\"Fixture\"}", "report", "r-1");
        store.inputHash = new byte[32];
        store.inputHash[0] = 1;
        assertTrue(reads.published("report", "r-1").isEmpty());
    }

    private static ActionContext action(String key) { return new ActionContext(key, "r-1", null); }
    private static byte[] bodyHash(String body) {
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.CONTENT, CanonicalJson.parse(body)));
    }

    private final class Fixture implements ReviewPublicationService.Store {
        private final JdbcContentReads reads;
        List<JsonNode> targets = List.of(EVIDENCE, INTERMEDIATE);
        long evidenceRevision;
        long intermediateRevision;
        boolean evidenceValid = true;
        boolean complete = true;
        byte[] inputHash = Arrays.copyOf(INPUT_HASH, 32);
        Fixture(JdbcDataSource source) { reads = new JdbcContentReads(source); }
        Row row() { return reads.current("report", "r-1"); }
        @Override public Current lock(String kind, String id) {
            return snapshot(reads.current(kind, id));
        }
        @Override public Current snapshot(Row row) {
            if (row == null) return null;
            List<Dependency> dependencies = targets.stream().map(target ->
                    new Dependency(target, target.equals(EVIDENCE) ? evidenceRevision : intermediateRevision,
                            !target.equals(EVIDENCE) || evidenceValid)).toList();
            return new Current(row, new Frozen("job-1", inputHash, targets, complete),
                    dependencies, true);
        }
        @Override public Receipt committed(String key) {
            return jdbc.query("SELECT kind,content_id,revision,state,stamp_hash FROM receipts"
                    + " WHERE action_key=?", (rs, ignored) -> new Receipt(rs.getString(1),
                    rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)), key)
                    .stream().findFirst().orElse(null);
        }
        @Override public void approve(Row old, Stamp stamp, String key) {
            int changed = jdbc.update("UPDATE contents SET state='approved',revision=revision+1,"
                            + "review_hash_v1=body_hash_v1,review_dependency_stamp=?,review_stamp_hash=?,"
                            + "review_stamp_id=?,reviewed_input_hash=? WHERE kind=? AND id=? AND revision=?",
                    stamp.json(), HexFormat.of().parseHex(stamp.hash()), "stamp-" + key,
                    inputHash, old.kind(), old.id(), old.revision());
            assertEquals(1, changed);
            receipt(key, old, "approved", stamp.hash());
        }
        @Override public void publish(Row old, String key) {
            assertEquals(1, jdbc.update("UPDATE contents SET state='published',revision=revision+1,"
                    + "published_at=? WHERE kind=? AND id=? AND revision=?",
                    Timestamp.from(Instant.parse("2026-09-30T00:00:00Z")),
                    old.kind(), old.id(), old.revision()));
            receipt(key, old, "published", HexFormat.of().formatHex(old.reviewStampHash()));
        }
        @Override public void edit(Row old, String bodyJson, byte[] hash, String key) {
            assertEquals(1, jdbc.update("UPDATE contents SET body_json=?,body_hash_v1=?,"
                    + "body_revision=body_revision+1,revision=revision+1,state='draft',"
                    + "published_at=NULL,review_hash_v1=NULL,review_dependency_stamp=NULL,"
                    + "review_stamp_hash=NULL,review_stamp_id=NULL,reviewed_input_hash=NULL"
                    + " WHERE kind=? AND id=? AND revision=?", bodyJson, hash,
                    old.kind(), old.id(), old.revision()));
            receipt(key, old, "draft", null);
        }
        private void receipt(String key, Row old, String state, String hash) {
            jdbc.update("INSERT INTO receipts VALUES(?,?,?,?,?,?)", key, old.kind(),
                    old.id(), old.revision() + 1, state, hash);
        }
    }
}
