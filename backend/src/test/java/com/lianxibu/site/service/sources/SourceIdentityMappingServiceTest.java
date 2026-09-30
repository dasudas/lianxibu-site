package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lianxibu.site.repository.sources.JdbcSourceReads;
import com.lianxibu.site.service.sources.SourceIdentityMappingService.ReviewedMapping;
import com.lianxibu.site.service.sources.SourceIdentityMappingService.UnmappedSourceException;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SourceIdentityMappingServiceTest {
    private JdbcTemplate jdbc;
    private JdbcSourceReads store;
    private DataSourceTransactionManager tx;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-map-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),"
                + "name VARCHAR(200),state VARCHAR(16),config_json CLOB,draft_config_json CLOB,"
                + "effective_config_json CLOB,revision BIGINT,config_revision BIGINT,"
                + "activation_stage VARCHAR(16),config_valid_until TIMESTAMP,category VARCHAR(16))");
        store = new JdbcSourceReads(source);
        tx = new DataSourceTransactionManager(source);
    }

    @Test void reviewedSourcesResolveIndependentlyAndIdempotentlyWithoutChangingLegacyRows() {
        String alpha = "{\"url\":\"https://news.example.invalid/feed\",\"legacy\":true}";
        String beta = "{\"entry_url\":\"https://updates.example.invalid/rss\"}";
        insert("legacy:alpha", alpha, null);
        insert("legacy:beta", beta, null);
        var mapper = mapper(List.of(review("legacy:alpha", alpha, "https://news.example.invalid/feed"),
                review("legacy:beta", beta, "https://updates.example.invalid/rss")));
        assertEquals("legacy:alpha", mapper.writerIdentity("legacy:alpha").sourceId());
        assertEquals("legacy:beta", mapper.writerIdentity("legacy:beta").sourceId());
        assertEquals(mapper.writerIdentity("legacy:alpha"), mapper.writerIdentity("legacy:alpha"));
        assertEquals(alpha, store.byId("legacy:alpha").legacyConfigJson());
        assertEquals(beta, store.byId("legacy:beta").legacyConfigJson());
        assertEquals(1L, store.byId("legacy:alpha").revision());
        assertEquals(null, store.byId("legacy:alpha").draftConfigJson());
        assertEquals("disabled", store.byId("legacy:alpha").activationStage());
    }

    @Test void unknownChangedAndMisidentifiedSourcesAreDeniedWithoutBlockingOtherSources() {
        String blog = "{\"url\":\"https://blog.example.invalid/feed\"}";
        String good = "{\"url\":\"https://news.example.invalid/feed\"}";
        insert("legacy:blog", blog, null);
        insert("legacy:good", good, null);
        var mapper = mapper(List.of(review("legacy:blog", blog,
                        "https://api.example.invalid/repos/project/releases"),
                review("legacy:good", good, "https://news.example.invalid/feed")));
        assertThrows(UnmappedSourceException.class, () -> mapper.writerIdentity("legacy:blog"));
        assertThrows(UnmappedSourceException.class, () -> mapper.writerIdentity("unknown"));
        assertEquals("legacy:good", mapper.writerIdentity("legacy:good").sourceId());
        jdbc.update("UPDATE sources SET config_json=? WHERE id=?",
                "{\"url\":\"https://news.example.invalid/changed\"}", "legacy:good");
        assertThrows(UnmappedSourceException.class, () -> mapper.writerIdentity("legacy:good"));
    }

    @Test void catalogAndCategoryConflictsFailClosed() {
        String json = "{\"entry_urls\":[\"https://feeds.example.invalid/rss\"]}";
        insert("legacy:one", json, "catalog");
        var one = review("legacy:one", json, "https://feeds.example.invalid/rss");
        assertThrows(IllegalArgumentException.class, () -> mapper(List.of(one, one)));
        assertThrows(IllegalArgumentException.class, () -> mapper(List.of(one,
                review("legacy:two", json, "https://feeds.example.invalid/rss"))));
        assertThrows(UnmappedSourceException.class, () -> mapper(List.of(one)).writerIdentity("legacy:one"));
        assertThrows(IllegalArgumentException.class, () -> new ReviewedMapping("legacy:bad", "article",
                SourceIdentityMappingService.sha256(json), "http://feeds.example.invalid/rss", "article"));
        assertThrows(UnmappedSourceException.class, () -> mapper(List.of()).writerIdentity("legacy:one"));
    }

    private SourceIdentityMappingService mapper(List<ReviewedMapping> approved) {
        return new SourceIdentityMappingService(store, tx, approved);
    }

    private static ReviewedMapping review(String id, String json, String entry) {
        return new ReviewedMapping(id, "article", SourceIdentityMappingService.sha256(json),
                entry, "article");
    }

    private void insert(String id, String json, String category) {
        jdbc.update("INSERT INTO sources(id,source_kind,name,state,config_json,draft_config_json,"
                        + "effective_config_json,revision,config_revision,activation_stage,"
                        + "config_valid_until,category) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "article", "Fictional", "enabled", json, null, null, 1, 0, "disabled", null, category);
    }
}
