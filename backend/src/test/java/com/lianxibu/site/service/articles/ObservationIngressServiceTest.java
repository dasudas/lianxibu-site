package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.articles.JdbcObservationStore;
import com.lianxibu.site.repository.articles.JdbcArticleWrites;
import com.lianxibu.site.repository.articles.JdbcArticleProjection;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.JdbcSourceRunStore;
import com.lianxibu.site.repository.sources.JdbcSourceReads;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.SourceIdentityMappingService;
import com.lianxibu.site.service.sources.SourceIdentityMappingService.ReviewedMapping;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ObservationIngressServiceTest {
    private final MutableClock time = new MutableClock("2026-01-03T00:00:00Z");
    private final TestNodes nodes = new TestNodes();
    private JdbcTemplate jdbc;
    private JdbcDataSource db;
    private SharedWriteExecutor writes;
    private JdbcSourceRunStore runs;
    private ObservationIngressService service;
    private boolean writerFails;

    @BeforeEach void setup() {
        db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:obs-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),"
                + "name VARCHAR(200),state VARCHAR(16),config_json CLOB,draft_config_json CLOB,"
                + "effective_config_json CLOB,revision BIGINT,config_revision BIGINT,"
                + "activation_stage VARCHAR(16),config_valid_until TIMESTAMP,category VARCHAR(16))");
        jdbc.update("INSERT INTO sources(id,source_kind,name,state,config_json,revision,"
                + "config_revision,activation_stage,category) VALUES(?,?,?,?,?,?,?,?,?)",
                "fixture:feed", "article", "Fixture", "enabled",
                "{\"url\":\"https://feeds.example.invalid/news\"}", 1, 1, "production", "article");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),"
                + "run_kind VARCHAR(32),business_date DATE,generation BIGINT,window_start TIMESTAMP,"
                + "window_end TIMESTAMP,config_version BIGINT,config_snapshot_json CLOB,"
                + "activation_stage VARCHAR(16),revision BIGINT,delivery_state VARCHAR(32),"
                + "claim_node_id VARCHAR(64),claim_token_hash BINARY(32),"
                + "claim_token_ciphertext BLOB,claim_key_id VARCHAR(64),lease_until TIMESTAMP,"
                + "manifest_hash BINARY(32),part_count BIGINT)");
        jdbc.update("INSERT INTO source_runs(id,source_id,run_kind,business_date,generation,"
                + "window_start,window_end,config_version,config_snapshot_json,activation_stage,"
                + "revision,delivery_state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                "run-fixture", "fixture:feed", "daily", java.sql.Date.valueOf("2026-01-02"),
                1, java.sql.Timestamp.from(Instant.parse("2026-01-01T16:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-01-02T16:00:00Z")),
                1, "{\"parser_version\":\"fixture-parser\",\"valid_until\":\"2027-01-01T00:00:00Z\"}",
                "production", 0, "receiving");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),"
                + "request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,"
                + "expires_at TIMESTAMP,id VARCHAR(64),receipt_kind VARCHAR(32),actor_id VARCHAR(64),"
                + "source_id VARCHAR(80),scope VARCHAR(128),key_hash BINARY(32),run_id VARCHAR(64),"
                + "member_id VARCHAR(64),observation_id VARCHAR(64),original_status VARCHAR(32),"
                + "current_status VARCHAR(32),ack_json CLOB,created_at TIMESTAMP,revision BIGINT,"
                + "PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,"
                + "revision BIGINT,run_id VARCHAR(64),member_id VARCHAR(64),source_id VARCHAR(80),"
                + "source_item_id CLOB,source_item_hash BINARY(32),category VARCHAR(16),"
                + "observation_id VARCHAR(64),node_id VARCHAR(64),payload_hash BINARY(32),"
                + "replaces_observation_id VARCHAR(64),evidence_json CLOB,evidence_hash BINARY(32),"
                + "article_id VARCHAR(64),article_revision_id VARCHAR(64),terminal_state VARCHAR(16),"
                + "receipt_ref VARCHAR(64),state VARCHAR(32),projection_decision VARCHAR(16),"
                + "source_order_json CLOB,validity_revision BIGINT,effective_valid INT,"
                + "exclusion_reason VARCHAR(128),"
                + "UNIQUE(run_id,member_id),UNIQUE(run_id,source_item_hash))");
        jdbc.execute("CREATE TABLE fixture_articles(id VARCHAR(64) PRIMARY KEY,source_item_id VARCHAR(200))");
        jdbc.execute("CREATE TABLE fixture_revisions(id VARCHAR(64) PRIMARY KEY,article_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80) NOT NULL,"
                + "external_id VARCHAR(255) NOT NULL,external_key BINARY(32) NOT NULL,"
                + "title VARCHAR(512) NOT NULL,url VARCHAR(2000) NOT NULL,published_at TIMESTAMP,"
                + "summary CLOB,body_text CLOB,facts_json CLOB NOT NULL,content_hash BINARY(32),"
                + "revision BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,source_item_id CLOB,"
                + "source_item_hash BINARY(32),latest_revision_id VARCHAR(64),"
                + "projection_order_json CLOB,UNIQUE(source_id,external_key),"
                + "UNIQUE(source_id,source_item_hash))");
        jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,"
                + "revision BIGINT,article_id VARCHAR(64),revision_no BIGINT,content_hash BINARY(32),"
                + "title VARCHAR(512),source_summary CLOB,body_text CLOB,source_time TIMESTAMP,"
                + "time_basis VARCHAR(32),content_status VARCHAR(32),body_truncated INT,"
                + "parser_version VARCHAR(64),config_version BIGINT,qualification_events_json CLOB,"
                + "source_order_json CLOB,legacy_flag INT,UNIQUE(article_id,revision_no))");
        runs = new JdbcSourceRunStore(db);
        writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(db),
                new DataSourceTransactionManager(db), () -> 25, ignored -> { });
        service = new ObservationIngressService(new JdbcObservationStore(db), runId -> {
            var row = runs.locked(runId);
            return row == null ? null : new ObservationIngressService.RunSnapshot(row.id(),
                    row.sourceId(), row.businessDate(), row.configVersion(),
                    row.configSnapshotJson(), row.activationStage(), row.deliveryState());
        }, writes, nodes, (input, day) -> {
            boolean inserted = count("fixture_articles") == 0;
            if (inserted) {
                jdbc.update("INSERT INTO fixture_articles VALUES(?,?)", "article-fixture", input.sourceItemId());
                jdbc.update("INSERT INTO fixture_revisions VALUES(?,?)", "revision-fixture", "article-fixture");
            }
            if (writerFails) throw new IllegalStateException("injected revision failure");
            return new ObservationIngressService.ArticleOutcome("article-fixture",
                    "revision-fixture", input.title().startsWith("Disputed") ? "needs_review"
                    : inserted ? "initialized" : "unchanged", 1);
        }, time);
    }

    @Test void articleRevisionEvidenceAndPermanentAckCommitTogether() {
        byte[] raw = article("obs-one", "Title", "run-fixture", "member-one");
        var accepted = service.accept("node-fixture", raw, action("obs-one"));
        assertEquals("accepted", accepted.status());
        assertEquals(1L, count("fixture_articles"));
        assertEquals(1L, count("fixture_revisions"));
        assertEquals(1L, count("source_run_items"));
        assertEquals(1L, count("request_receipts"));
        assertEquals(accepted.receiptRef(), jdbc.queryForObject(
                "SELECT receipt_ref FROM source_run_items", String.class));
        time.set("2026-04-05T00:00:00Z");
        jdbc.update("UPDATE source_runs SET delivery_state='complete' WHERE id='run-fixture'");
        var duplicate = service.accept("node-fixture", raw, action("obs-one"));
        assertEquals("duplicate", duplicate.status());
        assertEquals(accepted.receiptRef(), duplicate.receiptRef());
        assertEquals(accepted.acceptedAt(), duplicate.acceptedAt());
        assertEquals(1L, count("fixture_revisions"));
        assertEquals(1L, count("source_run_items"));
    }

    @Test void conflictingIdentityAndRevokedNodeNeverReplaySuccess() {
        byte[] original = article("obs-one", "Title", "run-fixture", "member-one");
        service.accept("node-fixture", original, action("obs-one"));
        assertThrows(ObservationIngressService.IdentityConflict.class,
                () -> service.accept("node-fixture",
                        article("obs-one", "Changed", "run-fixture", "member-one"), action("obs-one")));
        nodes.revoked = true;
        assertThrows(SecurityException.class,
                () -> service.accept("node-fixture", original, action("obs-one")));
        assertEquals(1L, count("request_receipts"));
    }

    @Test void historicalConfigBlocksAndClosedRunRejectsNewIdentity() {
        jdbc.update("UPDATE source_runs SET config_snapshot_json=? WHERE id='run-fixture'",
                "{\"parser_version\":\"old-parser\",\"valid_until\":\"2026-01-01T00:00:00Z\"}");
        var blocked = service.accept("node-fixture", article("obs-old", "Title", "run-fixture", "member-old"),
                action("obs-old"));
        assertEquals("blocked", blocked.status());
        assertEquals("historical_config_unverifiable", blocked.code());
        assertEquals(0L, count("source_run_items"));
        assertEquals(0L, count("fixture_revisions"));
        assertEquals("blocked", service.accept("node-fixture",
                article("obs-old", "Title", "run-fixture", "member-old"),
                action("obs-old")).status());
        jdbc.update("UPDATE source_runs SET delivery_state='complete', config_snapshot_json=?"
                + " WHERE id='run-fixture'",
                "{\"parser_version\":\"fixture-parser\",\"valid_until\":\"2027-01-01T00:00:00Z\"}");
        var rejected = service.accept("node-fixture",
                article("obs-new", "Title", "run-fixture", "member-new"), action("obs-new"));
        assertEquals("rejected", rejected.status());
        assertEquals("run_closed_unknown_observation", rejected.code());
    }

    @Test void revisionFailureRollsBackArticleItemAndReceipt() {
        writerFails = true;
        assertThrows(IllegalStateException.class, () -> service.accept("node-fixture",
                article("obs-fail", "Title", "run-fixture", "member-fail"), action("obs-fail")));
        assertEquals(0L, count("fixture_articles"));
        assertEquals(0L, count("fixture_revisions"));
        assertEquals(0L, count("source_run_items"));
        assertEquals(0L, count("request_receipts"));
        writerFails = false;
        assertEquals("accepted", service.accept("node-fixture",
                article("obs-fail", "Title", "run-fixture", "member-fail"),
                action("obs-fail")).status());
        assertEquals(1L, count("request_receipts"));
    }

    @Test void twoUploadsOfOneObservationProduceOneAcceptedAndOneDuplicate() throws Exception {
        byte[] raw = article("obs-race", "Title", "run-fixture", "member-race");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> call = () -> {
                start.await(2, TimeUnit.SECONDS);
                return service.accept("node-fixture", raw, action("obs-race")).status();
            };
            var first = pool.submit(call);
            var second = pool.submit(call);
            start.countDown();
            assertEquals(Set.of("accepted", "duplicate"),
                    Set.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS)));
        }
        assertEquals(1L, count("fixture_revisions"));
        assertEquals(1L, count("source_run_items"));
        assertEquals(1L, count("request_receipts"));
    }

    @Test void realCompatibilityWriterJoinsObservationTransaction() {
        var tx = new DataSourceTransactionManager(db);
        var identity = new SourceIdentityMappingService(new JdbcSourceReads(db), tx,
                List.of(new ReviewedMapping("fixture:feed", "article",
                        SourceIdentityMappingService.sha256(
                                "{\"url\":\"https://feeds.example.invalid/news\"}"),
                        "https://feeds.example.invalid/news", "article")));
        var writer = new ArticleCompatibilityWriter(new JdbcArticleWrites(db), identity,
                tx, time, List.of());
        var integrated = new ObservationIngressService(new JdbcObservationStore(db), runId -> {
            var row = runs.locked(runId);
            return row == null ? null : new ObservationIngressService.RunSnapshot(row.id(),
                    row.sourceId(), row.businessDate(), row.configVersion(),
                    row.configSnapshotJson(), row.activationStage(), row.deliveryState());
        }, writes, nodes, (input, day) -> {
            var compatible = writer.accept(input);
            var projected = new ArticleProjectionService(new JdbcArticleProjection(db),
                    List.of(new ArticleProjectionService.TrustedOrderRule("fixture:feed",
                            "fixture-parser", "revision_time", "fixture-domain")), time)
                    .decide(input, compatible, day);
            return new ObservationIngressService.ArticleOutcome(projected.articleId(),
                    projected.evidenceRevisionId(), projected.projectionDecision(),
                    projected.articleRevision());
        }, time);
        var ack = integrated.accept("node-fixture", orderedArticle(
                "obs-real", "Real writer fixture", "run-fixture", "member-real", null,
                "2026-01-02T00:00:00.000000Z"),
                action("obs-real"));
        assertEquals("accepted", ack.status());
        assertEquals("initialized", ack.projectionDecision());
        assertEquals(2L, ack.currentArticleRevision());
        assertEquals(1L, count("articles"));
        assertEquals(1L, count("article_revisions"));
        assertEquals(1L, count("source_run_items"));
        assertEquals(1L, count("request_receipts"));
        var corrected = integrated.accept("node-fixture", orderedArticle(
                "obs-real-next", "Newer title", "run-fixture", "member-real", "obs-real",
                "2026-01-02T01:00:00.000000Z"), action("obs-real-next"));
        assertEquals("promoted", corrected.projectionDecision());
        assertEquals(3L, corrected.currentArticleRevision());
        assertEquals(2L, count("article_revisions"));
        assertEquals("Newer title", jdbc.queryForObject("SELECT title FROM articles", String.class));
        assertEquals(2L, count("source_run_items"));
    }

    @Test void correctionPreservesOldEvidenceUntilManifestStarts() {
        service.accept("node-fixture", article("obs-first", "Original", "run-fixture", "member-one"),
                action("obs-first"));
        var correction = service.accept("node-fixture",
                article("obs-second", "Corrected", "run-fixture", "member-one", "obs-first"),
                action("obs-second"));
        assertEquals("accepted", correction.status());
        assertEquals(2L, count("source_run_items"));
        assertEquals(2L, count("request_receipts"));
        assertEquals("excluded", jdbc.queryForObject("SELECT terminal_state FROM source_run_items"
                + " WHERE observation_id='obs-first'", String.class));
        assertTrue(jdbc.queryForObject("SELECT evidence_json FROM source_run_items"
                + " WHERE observation_id='obs-first'", String.class).contains("Original"));
        assertEquals("accepted", jdbc.queryForObject("SELECT terminal_state FROM source_run_items"
                + " WHERE observation_id='obs-second'", String.class));
        jdbc.update("UPDATE source_runs SET manifest_hash=? WHERE id='run-fixture'", new byte[32]);
        assertThrows(ObservationIngressService.IdentityConflict.class, () -> service.accept(
                "node-fixture", article("obs-third", "Later", "run-fixture", "member-one", "obs-second"),
                action("obs-third")));
        assertEquals(2L, count("request_receipts"));
    }

    @Test void sameSourceItemInAnotherMemberCannotCreateSecondReceipt() {
        service.accept("node-fixture", article("obs-first", "Original", "run-fixture", "member-one"),
                action("obs-first"));
        assertThrows(ObservationIngressService.IdentityConflict.class, () -> service.accept(
                "node-fixture", article("obs-other", "Other", "run-fixture", "member-two"),
                action("obs-other")));
        assertEquals(1L, count("source_run_items"));
        assertEquals(1L, count("request_receipts"));
        assertEquals(1L, count("fixture_revisions"));
    }

    @Test void disputedProjectionIsAcceptedButNotReadyForAnalysis() {
        var ack = service.accept("node-fixture",
                article("obs-dispute", "Disputed evidence", "run-fixture", "member-dispute"),
                action("obs-dispute"));
        assertEquals("accepted", ack.status());
        assertEquals("needs_review", ack.projectionDecision());
        assertEquals("needs_review", jdbc.queryForObject("SELECT state FROM source_run_items",
                String.class));
    }

    @Test void catalogAndMetricContractVectorsPersistEvidenceWithoutArticle() throws Exception {
        jdbc.update("UPDATE source_runs SET config_snapshot_json=? WHERE id='run-fixture'",
                "{\"parser_version\":\"fixture-1\",\"valid_until\":\"2027-01-01T00:00:00Z\"}");
        JsonNode cases = CanonicalJson.parse(Files.readAllBytes(
                Path.of("../contracts/wire-examples.json"))).get("cases");
        for (String kind : List.of("catalog", "metric")) {
            ObjectNode vector = null;
            for (JsonNode item : cases) {
                if ("ObservationEnvelope".equals(item.get("schema").asText())
                        && item.get("valid").booleanValue()
                        && kind.equals(item.get("value").get("kind").asText())) {
                    vector = ((ObjectNode) item.get("value")).deepCopy();
                    break;
                }
            }
            assertNotNull(vector);
            String id = "obs-" + kind;
            vector.put("observation_id", id);
            vector.put("member_id", "member-" + kind);
            vector.put("run_id", "run-fixture");
            vector.put("business_date", "2026-01-02");
            vector.put("source_id", "fixture:feed");
            vector.put("source_item_id", "item-" + kind);
            vector.put("retrieved_at", "2026-01-02T00:00:00.000000Z");
            vector.remove("payload_hash");
            vector.put("payload_hash", CanonicalJson.hash(Domain.OBSERVATION, vector));
            assertEquals("accepted", service.accept("node-fixture", CanonicalJson.bytes(vector),
                    action(id)).status());
            if ("metric".equals(kind)) {
                ((ObjectNode) vector.get("payload")).put("score", "101");
                vector.remove("payload_hash");
                vector.put("payload_hash", CanonicalJson.hash(Domain.OBSERVATION, vector));
                byte[] invalid = CanonicalJson.bytes(vector);
                assertThrows(ObservationIngressService.IdentityConflict.class,
                        () -> service.accept("node-fixture", invalid, action(id)));
            }
        }
        assertEquals(2L, count("source_run_items"));
        assertEquals(2L, count("request_receipts"));
        assertEquals(0L, count("fixture_articles"));
    }

    @Test void identitySafeInvalidPayloadPersistsRejectedReceipt() {
        byte[] raw = article("obs-invalid", "Title", "run-fixture", "member-invalid");
        ObjectNode wire = ((ObjectNode) CanonicalJson.parse(raw)).deepCopy();
        ((ObjectNode) wire.get("payload")).put("source_url", "http://example.invalid/insecure");
        wire.remove("payload_hash");
        wire.put("payload_hash", CanonicalJson.hash(Domain.OBSERVATION, wire));
        byte[] invalid = CanonicalJson.bytes(wire);
        assertEquals("rejected", service.accept("node-fixture", invalid,
                action("obs-invalid")).status());
        assertEquals("invalid_payload", service.accept("node-fixture", invalid,
                action("obs-invalid")).code());
        assertEquals(1L, count("request_receipts"));
        assertEquals(0L, count("source_run_items"));
        assertEquals(0L, count("fixture_articles"));
    }

    private byte[] article(String observationId, String title, String runId, String memberId) {
        return article(observationId, title, runId, memberId, null);
    }
    private byte[] article(String observationId, String title, String runId, String memberId,
                           String replaces) {
        ObjectNode root = JsonMapper.shared().createObjectNode();
        root.put("protocol_revision", "0.11");
        root.put("observation_id", observationId);
        root.put("member_id", memberId);
        root.put("run_id", runId);
        root.put("business_date", "2026-01-02");
        root.put("source_id", "fixture:feed");
        root.put("source_revision", 1);
        root.put("source_item_id", "fixture-item");
        root.put("kind", "article");
        root.put("retrieved_at", "2026-01-02T00:00:00.000000Z");
        if (replaces == null) root.putNull("replaces_observation_id");
        else root.put("replaces_observation_id", replaces);
        ObjectNode payload = root.putObject("payload");
        payload.put("title", title);
        payload.put("source_url", "https://example.invalid/article");
        payload.putNull("published_at");
        payload.putNull("updated_at");
        payload.put("time_basis", "unknown");
        payload.putNull("source_summary");
        payload.put("body_text", "Fixture body");
        payload.put("content_status", "full");
        payload.put("body_truncated", false);
        payload.put("parser_version", "fixture-parser");
        payload.put("config_version", 1);
        ObjectNode order = payload.putObject("source_order");
        order.put("basis", "unknown");
        order.putNull("domain");
        order.putNull("value");
        order.putNull("evidence_ref");
        root.put("payload_hash", CanonicalJson.hash(Domain.OBSERVATION, root));
        return CanonicalJson.bytes(root);
    }
    private byte[] orderedArticle(String observationId, String title, String runId,
                                  String memberId, String replaces, String sourceTime) {
        ObjectNode root = ((ObjectNode) CanonicalJson.parse(
                article(observationId, title, runId, memberId, replaces))).deepCopy();
        ObjectNode order = (ObjectNode) root.get("payload").get("source_order");
        order.put("basis", "revision_time");
        order.put("domain", "fixture-domain");
        order.put("value", sourceTime);
        order.put("evidence_ref", "fixture-proof");
        root.remove("payload_hash");
        root.put("payload_hash", CanonicalJson.hash(Domain.OBSERVATION, root));
        return CanonicalJson.bytes(root);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private static ActionContext action(String observationId) {
        return new ActionContext(observationId, "receipt-" + observationId, null);
    }
    private static final class TestNodes implements ObservationIngressService.NodeAuthorizer {
        boolean revoked;
        @Override public Principal authorize(String bearer, String sourceId) {
            if (revoked || !"node-fixture".equals(bearer) || !"fixture:feed".equals(sourceId)) {
                throw new SecurityException("node permission denied");
            }
            return new Principal("node-fixture", List.of(sourceId), List.of("observation:write"), 1);
        }
    }
    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(String initial) { set(initial); }
        void set(String text) { now = Instant.parse(text); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
