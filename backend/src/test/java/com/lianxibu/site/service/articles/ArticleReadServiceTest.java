package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.articles.JdbcArticleReads;
import java.sql.Timestamp;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ArticleReadServiceTest {
    private JdbcTemplate jdbc;
    private ArticleReadService reads;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:articles-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,name VARCHAR(200))");
        jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),"
                + "title VARCHAR(512),url VARCHAR(2000),published_at TIMESTAMP,summary CLOB,"
                + "body_text CLOB,updated_at TIMESTAMP,revision BIGINT)");
        jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),"
                + "title VARCHAR(512),summary CLOB,first_recorded_at TIMESTAMP,"
                + "first_seen_business_date DATE,last_seen_at TIMESTAMP,occurrence_count BIGINT,"
                + "repeat_count BIGINT,analysis_state VARCHAR(32),revision BIGINT)");
        reads = new ArticleReadService(new JdbcArticleReads(source), new DataSourceTransactionManager(source));
        jdbc.update("INSERT INTO sources VALUES (?,?)", "src-fixture", "Fixture Source");
    }

    @Test void independentSummaryAndOriginalLinkReachProjectionButBodyDoesNot() {
        article("a", "Title", "Independent introduction", "INTERNAL_SOURCE_BODY_SENTINEL");
        var row = reads.article("a").orElseThrow();
        assertEquals("Independent introduction", row.summary());
        assertEquals("https://example.invalid/original/a", row.sourceUrl());
        assertEquals("Fixture Source", row.sourceName());
        assertFalse(row.toString().contains("INTERNAL_SOURCE_BODY_SENTINEL"));
        assertFalse(java.util.Arrays.stream(row.getClass().getRecordComponents())
                .anyMatch(component -> component.getName().equals("bodyText")));
    }

    @Test void noSummaryNeverFallsBackToTruncatedBodyAndPagingIsStable() {
        article("a", "100% title", null, "INTERNAL_SOURCE_BODY_SENTINEL");
        article("b", "Other", "Visible", "OTHER_INTERNAL_BODY");
        assertNull(reads.article("a").orElseThrow().summary());
        assertEquals(2, reads.articles(null, null, 1, 1).total());
        assertEquals("a", reads.articles(null, null, 1, 1).items().getFirst().id());
        assertEquals("b", reads.articles(null, null, 2, 1).items().getFirst().id());
        assertEquals(1, reads.articles("src-fixture", "100%", 1, 10).total());
        assertEquals(0, reads.articles(null, "body", 1, 10).total());
    }

    @Test void entityReadPreservesNullableLegacyCounts() {
        jdbc.update("INSERT INTO analyzed_entities(id,category,title,summary,first_recorded_at,"
                        + "first_seen_business_date,last_seen_at,occurrence_count,repeat_count,"
                        + "analysis_state,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "entity-fixture", "agent", "Fixture", null, Timestamp.from(Instant.EPOCH),
                null, null, null, null, "pending", 0);
        assertNull(reads.entity("entity-fixture").orElseThrow().occurrenceCount());
        assertEquals(1, reads.entities("agent", null, 1, 10).total());
    }

    private void article(String id, String title, String summary, String body) {
        jdbc.update("INSERT INTO articles(id,source_id,title,url,published_at,summary,body_text,"
                        + "updated_at,revision) VALUES (?,?,?,?,?,?,?,?,?)", id, "src-fixture", title,
                "https://example.invalid/original/" + id,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), summary, body,
                Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")), 1);
    }
}
