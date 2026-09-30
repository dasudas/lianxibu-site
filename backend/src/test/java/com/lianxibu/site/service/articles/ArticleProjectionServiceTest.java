package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.articles.JdbcArticleProjection;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter.Input;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter.Result;
import com.lianxibu.site.service.articles.ArticleProjectionService.TrustedOrderRule;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ArticleProjectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-04T00:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-01-03");
    private JdbcTemplate jdbc;
    private JdbcArticleProjection store;
    private ArticleProjectionService service;
    private SharedWriteExecutor writes;

    @BeforeEach void setup() {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:projection-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,title VARCHAR(512),"
                + "summary CLOB,body_text CLOB,published_at TIMESTAMP,facts_json CLOB,"
                + "content_hash BINARY(32),revision BIGINT,updated_at TIMESTAMP,"
                + "latest_revision_id VARCHAR(64),projection_order_json CLOB)");
        jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,"
                + "created_at TIMESTAMP,revision BIGINT,article_id VARCHAR(64),revision_no BIGINT,"
                + "content_hash BINARY(32),title VARCHAR(512),source_summary CLOB,body_text CLOB,"
                + "source_time TIMESTAMP,published_at TIMESTAMP,updated_at TIMESTAMP,submitted_at TIMESTAMP,time_basis VARCHAR(32),content_status VARCHAR(32),"
                + "body_truncated INT,parser_version VARCHAR(64),config_version BIGINT,"
                + "qualification_events_json CLOB,source_order_json CLOB,legacy_flag INT,"
                + "UNIQUE(article_id,revision_no))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),"
                + "request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,"
                + "expires_at TIMESTAMP,id VARCHAR(64),receipt_kind VARCHAR(32),actor_id VARCHAR(64),"
                + "scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),"
                + "current_status VARCHAR(32),result_ref_json CLOB,created_at TIMESTAMP,revision BIGINT,"
                + "PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        jdbc.update("INSERT INTO articles VALUES(?,?,?,?,?,?,?,?,?,?,?)", "article-one", "Title",
                "Summary", "Body B", java.sql.Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")),
                "{}", hash("Title", "Summary", "Body B"), 1,
                java.sql.Timestamp.from(NOW), "revision-one", order("2026-01-03T00:00:00.000000Z"));
        jdbc.update("INSERT INTO article_revisions(id,created_at,revision,article_id,revision_no,content_hash,title,source_summary,body_text,source_time,time_basis,content_status,body_truncated,parser_version,config_version,qualification_events_json,source_order_json,legacy_flag) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "revision-one", java.sql.Timestamp.from(NOW), 0, "article-one", 1,
                hash("Title", "Summary", "Body B"), "Title", "Summary", "Body B",
                java.sql.Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")),
                "updated", "full", 0, "fixture-parser", 1, "[]",
                order("2026-01-03T00:00:00.000000Z"), 0);
        store = new JdbcArticleProjection(db);
        service = new ArticleProjectionService(store,
                List.of(new TrustedOrderRule("fixture:feed", "fixture-parser",
                        "revision_time", "fixture-domain")), Clock.fixed(NOW, ZoneOffset.UTC));
        writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(db),
                new DataSourceTransactionManager(db), () -> 25, ignored -> { });
    }

    @Test void newerDifferentBodyPromotesAndOlderArrivalStaysHistorical() {
        var newer = decide("newer", "Body C", "2026-01-03T02:00:00.000000Z");
        assertEquals("promoted", newer.projectionDecision());
        assertEquals(newer.evidenceRevisionId(), newer.currentRevisionId());
        assertEquals("Body C", currentBody());
        assertTrue(jdbc.queryForObject("SELECT qualification_events_json FROM article_revisions"
                + " WHERE id=?", String.class, newer.evidenceRevisionId()).contains("material_update"));
        var older = decide("older", "Body A", "2026-01-03T01:00:00.000000Z");
        assertEquals("historical", older.projectionDecision());
        assertNotEquals(older.evidenceRevisionId(), older.currentRevisionId());
        assertEquals("Body C", currentBody());
        assertEquals(3L, count("article_revisions"));
        var repeated = decide("older-repeat", "Body A", "2026-01-03T01:00:00.000000Z");
        assertEquals("historical", repeated.projectionDecision());
        assertEquals(older.evidenceRevisionId(), repeated.evidenceRevisionId());
        assertEquals(3L, count("article_revisions"));
        var restored = decide("restored", "Body A", "2026-01-03T03:00:00.000000Z");
        assertEquals("promoted", restored.projectionDecision());
        assertNotEquals(older.evidenceRevisionId(), restored.evidenceRevisionId());
        assertEquals("Body A", currentBody());
        assertEquals(4L, count("article_revisions"));
    }

    @Test void sameBodyNewerOrderOnlyAdvancesWatermark() {
        var same = decide("same", "Body B", "2026-01-03T03:00:00.000000Z");
        assertEquals("unchanged", same.projectionDecision());
        assertEquals(1L, count("article_revisions"));
        assertEquals(2, same.articleRevision());
        var intermediate = decide("intermediate", "Body X", "2026-01-03T02:00:00.000000Z");
        assertEquals("historical", intermediate.projectionDecision());
        assertEquals("Body B", currentBody());
    }

    @Test void newlyTrustedSourceTimeCreatesQualificationRevision() {
        jdbc.update("UPDATE article_revisions SET source_time=NULL,time_basis='unknown'"
                + " WHERE id='revision-one'");
        jdbc.update("UPDATE articles SET published_at=NULL WHERE id='article-one'");
        var qualified = decide("time-qualified", "Body B", "2026-01-03T02:00:00.000000Z");
        assertEquals("promoted", qualified.projectionDecision());
        assertNotEquals("revision-one", qualified.evidenceRevisionId());
        assertTrue(jdbc.queryForObject("SELECT qualification_events_json FROM article_revisions"
                + " WHERE id=?", String.class, qualified.evidenceRevisionId()).contains("time_qualified"));
        assertEquals(2L, count("article_revisions"));
    }

    @Test void equalOrUnknownOrderNeedsReviewAndManualCasRejectsStaleRevision() {
        var equal = decide("equal", "Different", "2026-01-03T00:00:00.000000Z");
        assertEquals("needs_review", equal.projectionDecision());
        assertEquals("Body B", currentBody());
        assertEquals(1, store.current("article-one").revision());
        assertThrows(ArticleProjectionService.ProjectionConflict.class,
                () -> writes.execute(new ActionContext("manual-stale", "manual-stale", null),
                        ignored -> null,
                        ignored -> service.confirmPromote("fixture-owner", "manual-stale",
                                "article-one", equal.evidenceRevisionId(),
                                0, "reviewed source proof")));
        assertEquals("Body B", currentBody());
        var manual = writes.execute(new ActionContext("manual-valid", "manual-valid", null),
                ignored -> null,
                ignored -> service.confirmPromote("fixture-owner", "manual-valid",
                        "article-one", equal.evidenceRevisionId(),
                        1, "reviewed source proof"));
        assertEquals("Different", currentBody());
        assertEquals(2, manual.articleRevision());
        assertNotEquals(equal.evidenceRevisionId(), manual.currentRevisionId());
        assertEquals(1L, count("request_receipts"));
        assertTrue(jdbc.queryForObject("SELECT result_ref_json FROM request_receipts",
                String.class).contains("reviewed source proof"));
        assertEquals("[]", jdbc.queryForObject("SELECT qualification_events_json"
                + " FROM article_revisions WHERE id=?", String.class, manual.currentRevisionId()));
        var replay = writes.execute(new ActionContext("manual-valid", "manual-valid", null),
                ignored -> null,
                ignored -> service.confirmPromote("fixture-owner", "manual-valid",
                        "article-one", equal.evidenceRevisionId(), 1, "reviewed source proof"));
        assertEquals(manual, replay);
        assertEquals(1L, count("request_receipts"));
        assertThrows(ArticleProjectionService.ProjectionConflict.class,
                () -> writes.execute(new ActionContext("manual-valid", "manual-valid", null),
                        ignored -> null,
                        ignored -> service.confirmPromote("fixture-owner", "manual-valid",
                                "article-one", equal.evidenceRevisionId(), 1, "different reason")));
        var unknown = decide("unknown", "Unknown body", null);
        assertEquals("needs_review", unknown.projectionDecision());
        assertEquals("Different", currentBody());
    }

    @Test void unreviewedSourceOrderCannotPromote() {
        var unreviewed = new ArticleProjectionService(store, List.of(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var result = writes.execute(new ActionContext("unreviewed", "unreviewed", null),
                ignored -> null,
                ignored -> unreviewed.decide(input("Different", "2026-01-04T00:00:00.000000Z"),
                        new Result("article-one", "revision-one", false, false), DAY));
        assertEquals("needs_review", result.projectionDecision());
        assertEquals("Body B", currentBody());
    }

    private ArticleProjectionService.Decision decide(String action, String body, String time) {
        return writes.execute(new ActionContext(action, "resource-" + action, null),
                ignored -> null,
                ignored -> service.decide(input(body, time),
                        new Result("article-one", store.current("article-one").selectedRevisionId(),
                                false, false), DAY));
    }
    private static Input input(String body, String time) {
        return new Input("fixture:feed", "item-one", "Title", "https://example.invalid/article",
                time == null ? null : Instant.parse("2026-01-02T00:00:00Z"), "Summary", body, "updated", "full", false,
                "fixture-parser", 1, time == null ? "{\"basis\":\"unknown\",\"domain\":null,"
                + "\"value\":null,\"evidence_ref\":null}" : order(time), time == null ? null : Instant.parse(time), null);
    }
    private static String order(String time) {
        return "{\"basis\":\"revision_time\",\"domain\":\"fixture-domain\","
                + "\"value\":\"" + time + "\",\"evidence_ref\":\"fixture-evidence\"}";
    }
    private static byte[] hash(String title, String summary, String body) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("title", title);
        value.put("summary", summary);
        value.put("body_text", body);
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.ARTICLE_PROJECTION, value));
    }
    private String currentBody() {
        return jdbc.queryForObject("SELECT body_text FROM articles WHERE id='article-one'", String.class);
    }
    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
