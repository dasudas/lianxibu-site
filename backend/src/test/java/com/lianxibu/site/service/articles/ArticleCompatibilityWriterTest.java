package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianxibu.site.repository.articles.JdbcArticleWrites;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter.Input;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter.ReviewedLegacyArticle;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter.SourceMappingRequired;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter.IdentityConflict;
import com.lianxibu.site.repository.sources.JdbcSourceReads;
import com.lianxibu.site.service.sources.SourceIdentityMappingService;
import com.lianxibu.site.service.sources.SourceIdentityMappingService.ReviewedMapping;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ArticleCompatibilityWriterTest {
    private JdbcTemplate jdbc;
    private JdbcArticleWrites articles;
    private JdbcSourceReads sources;
    private DataSourceTransactionManager tx;
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final String config = "{\"url\":\"https://feeds.example.invalid/news\"}";

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:article-write-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id VARCHAR(64) PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock(id) VALUES(?)", "only-slot");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),"
                + "name VARCHAR(200),state VARCHAR(16),config_json CLOB,draft_config_json CLOB,"
                + "effective_config_json CLOB,revision BIGINT,config_revision BIGINT,"
                + "activation_stage VARCHAR(16),config_valid_until TIMESTAMP,category VARCHAR(16))");
        jdbc.update("INSERT INTO sources(id,source_kind,name,state,config_json,revision,"
                + "config_revision,activation_stage) VALUES(?,?,?,?,?,?,?,?)",
                "fixture:feed", "article", "Fixture", "enabled", config, 1, 0, "disabled");
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
                + "source_order_json CLOB,legacy_flag INT,published_at TIMESTAMP,updated_at TIMESTAMP,submitted_at TIMESTAMP,UNIQUE(article_id,revision_no))");
        articles = new JdbcArticleWrites(source);
        sources = new JdbcSourceReads(source);
        tx = new DataSourceTransactionManager(source);
    }

    @Test void reviewedLegacyConversionKeepsOldIdentityAndBodyAndCreatesOneBaseline() {
        insertOld("legacy:one", "old-1", "Old title", "Original body");
        var original = articles.byExternal("fixture:feed", "old-1");
        var writer = writer(List.of(review(original)));
        Input input = input("stable-1");
        var result = writer.accept(input);
        assertEquals("legacy:one", result.articleId());
        assertTrue(result.legacyBaselineCreated());
        assertFalse(result.inserted());
        var converted = articles.byExternal("fixture:feed", "old-1");
        assertArrayEquals(original.externalKey(), converted.externalKey());
        assertArrayEquals(original.oldContentHash(), converted.oldContentHash());
        assertEquals(original.body(), converted.body());
        assertEquals(original.revision(), converted.revision());
        assertEquals("stable-1", converted.sourceItemId());
        assertEquals(result.revisionId(), converted.latestRevisionId());
        assertEquals("[]", jdbc.queryForObject("SELECT qualification_events_json FROM article_revisions"
                + " WHERE id=?", String.class, result.revisionId()));
        assertEquals(1, jdbc.queryForObject("SELECT legacy_flag FROM article_revisions"
                + " WHERE id=?", Integer.class, result.revisionId()));
        var again = writer.accept(input);
        assertEquals(result.articleId(), again.articleId());
        assertEquals(result.revisionId(), again.revisionId());
        assertFalse(again.legacyBaselineCreated());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM articles", Long.class));
    }

    @Test void publishedAndUpdatedTimesStayIndependentAtInitialization() {
        var published=Instant.parse("2025-12-30T00:00:00Z");
        var updated=Instant.parse("2025-12-31T00:00:00Z");
        var value=new Input("fixture:feed","independent-times","Title",
                "https://example.invalid/article",published,"Summary","Body","updated",
                "full",false,"fixture-parser",1,null,updated,null);
        var result=writer(List.of()).accept(value);
        assertEquals(published,jdbc.queryForObject("SELECT published_at FROM articles WHERE id=?",Timestamp.class,result.articleId()).toInstant());
        assertEquals(updated,jdbc.queryForObject("SELECT source_time FROM article_revisions WHERE id=?",Timestamp.class,result.revisionId()).toInstant());
        assertEquals(published,jdbc.queryForObject("SELECT published_at FROM article_revisions WHERE id=?",Timestamp.class,result.revisionId()).toInstant());
        assertEquals(updated,jdbc.queryForObject("SELECT updated_at FROM article_revisions WHERE id=?",Timestamp.class,result.revisionId()).toInstant());
    }
    @Test void articleCharacterLimitsCountCodePointsAndKeepByteLimit() {
        // H2 VARCHAR measures UTF-16 units; production MySQL utf8mb4 measures characters.
        jdbc.execute("ALTER TABLE articles ALTER COLUMN title VARCHAR(1024)");
        jdbc.execute("ALTER TABLE article_revisions ALTER COLUMN title VARCHAR(1024)");
        String symbol=new String(Character.toChars(0x1F600));
        var value=new Input("fixture:feed","unicode-item",symbol.repeat(512),
                "https://example.invalid/article",null,null,symbol.repeat(50000),"unknown",
                "full",false,"fixture-parser",1,null);
        var saved=writer(List.of()).accept(value);
        assertEquals(symbol.repeat(512),jdbc.queryForObject("SELECT title FROM articles WHERE id=?",String.class,saved.articleId()));
        assertThrows(IllegalArgumentException.class,()->writer(List.of()).accept(new Input("fixture:feed","too-long",symbol.repeat(513),
                "https://example.invalid/article",null,null,null,"unknown","unavailable",false,"fixture-parser",1,null)));
        assertThrows(IllegalArgumentException.class,()->writer(List.of()).accept(new Input("fixture:feed","body-too-long","Title",
                "https://example.invalid/article",null,null,symbol.repeat(50001),"unknown","full",false,"fixture-parser",1,null)));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM articles",Long.class));
    }
    @Test void submittedEvidenceNeverBecomesArticlePublication() {
        var writer=writer(List.of());
        var submitted=Instant.parse("2026-09-30T00:00:00Z");
        var value=new Input("fixture:feed","fictional-hn-item","Fictional HN",
                "https://example.invalid/story",null,null,null,"submitted","unavailable",
                false,"global-hn-v1",1,null,null,submitted);
        var result=writer.accept(value);
        assertNull(jdbc.queryForObject("SELECT published_at FROM articles WHERE id=?",java.sql.Timestamp.class,result.articleId()));
        assertEquals(submitted,jdbc.queryForObject("SELECT submitted_at FROM article_revisions WHERE id=?",java.sql.Timestamp.class,result.revisionId()).toInstant());
        assertEquals(submitted,jdbc.queryForObject("SELECT source_time FROM article_revisions WHERE id=?",java.sql.Timestamp.class,result.revisionId()).toInstant());
        assertNull(jdbc.queryForObject("SELECT published_at FROM article_revisions WHERE id=?",java.sql.Timestamp.class,result.revisionId()));
        var bad=new Input("fixture:feed","bad-hn-item","Fictional HN",
                "https://example.invalid/story",submitted,null,null,"submitted","unavailable",
                false,"global-hn-v1",1,null,null,submitted);
        assertThrows(IllegalArgumentException.class,()->writer.accept(bad));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM articles",Long.class));
    }

    @Test void newInsertFillsOldRequiredColumnsAndIsIdempotent() {
        var writer = writer(List.of());
        var result = writer.accept(input("brand-new"));
        assertTrue(result.inserted());
        var row = articles.byExternal("fixture:feed", "brand-new");
        assertNotNull(row.externalKey());
        assertNotNull(row.factsJson());
        assertNotNull(row.oldContentHash());
        assertEquals("brand-new", row.sourceItemId());
        assertEquals(result.revisionId(), row.latestRevisionId());
        assertEquals(1L, row.revision());
        assertEquals(0, jdbc.queryForObject("SELECT legacy_flag FROM article_revisions"
                + " WHERE id=?", Integer.class, result.revisionId()));
        assertEquals("[]", jdbc.queryForObject("SELECT qualification_events_json FROM article_revisions"
                + " WHERE id=?", String.class, result.revisionId()));
        var again = writer.accept(input("brand-new"));
        assertEquals(result.articleId(), again.articleId());
        assertFalse(again.inserted());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM articles", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions", Long.class));
    }

    @Test void missingMappingAndDualIdentityConflictsNeverCreateDuplicates() {
        insertOld("legacy:one", "old-1", "Old", "Body");
        assertThrows(SourceMappingRequired.class, () -> writer(List.of()).accept(input("new-id")));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions", Long.class));
        var old = articles.byExternal("fixture:feed", "old-1");
        var approved = writer(List.of(review(old)));
        assertThrows(IdentityConflict.class, () -> approved.accept(input("old-1")));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM articles", Long.class));
        jdbc.update("UPDATE articles SET body_text=? WHERE id=?", "Changed", "legacy:one");
        assertThrows(SourceMappingRequired.class, () -> approved.accept(input("stable-1")));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions", Long.class));
    }

    @Test void failedBaselineRevisionRollsBackTheNewArticle() {
        jdbc.execute("ALTER TABLE article_revisions ADD CONSTRAINT ck_fixture_failure "
                + "CHECK(parser_version <> 'fixture-parser')");
        assertThrows(IdentityConflict.class, () -> writer(List.of()).accept(input("rollback-item")));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM articles", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions", Long.class));
    }

    private ArticleCompatibilityWriter writer(List<ReviewedLegacyArticle> legacy) {
        var sourceMapping = new SourceIdentityMappingService(sources, tx,
                List.of(new ReviewedMapping("fixture:feed", "article",
                        SourceIdentityMappingService.sha256(config),
                        "https://feeds.example.invalid/news", "article")));
        return new ArticleCompatibilityWriter(articles, sourceMapping, tx,
                Clock.fixed(now, ZoneOffset.UTC), legacy);
    }

    private static Input input(String itemId) {
        return new Input("fixture:feed", itemId, "New title",
                "https://items.example.invalid/new", null, "Summary", "New body", "unknown",
                "full", false, "fixture-parser", 1, null);
    }

    private static ReviewedLegacyArticle review(JdbcArticleWrites.Row row) {
        return new ReviewedLegacyArticle(row.id(), row.sourceId(), row.externalId(),
                "stable-1", ArticleCompatibilityWriter.snapshotSha256(row));
    }

    private void insertOld(String id, String externalId, String title, String body) {
        byte[] oldKey = new byte[32];
        oldKey[0] = 7;
        byte[] oldHash = new byte[32];
        oldHash[0] = 9;
        jdbc.update("INSERT INTO articles(id,source_id,external_id,external_key,title,url,"
                        + "published_at,summary,body_text,facts_json,content_hash,revision,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "fixture:feed", externalId, oldKey, title,
                "https://items.example.invalid/old", Timestamp.from(now), "Old summary", body,
                "{}", oldHash, 3, Timestamp.from(now));
    }
}
