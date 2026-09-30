package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.sources.JdbcSourceReads;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SourceReadServiceTest {
    private JdbcTemplate jdbc;
    private SourceReadService reads;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:sources-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),"
                + "name VARCHAR(200),state VARCHAR(16),config_json CLOB,draft_config_json CLOB,"
                + "effective_config_json CLOB,revision BIGINT,config_revision BIGINT,"
                + "activation_stage VARCHAR(16),config_valid_until TIMESTAMP,category VARCHAR(16))");
        reads = new SourceReadService(new JdbcSourceReads(source),
                new DataSourceTransactionManager(source),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test void legacyIdAndJsonStayReadableButEnabledDoesNotGrantProduction() {
        String config = "{\"url\":\"https://react.dev/rss.xml\",\"legacy_flag\":true}";
        insert("legacy:react-blog", "React Releases", "enabled", config, "disabled", null, 0);
        var row = reads.internalSource("legacy:react-blog").orElseThrow();
        assertEquals("legacy:react-blog", row.id());
        assertEquals(config, row.legacyConfigJson());
        assertFalse(reads.productionEligible(row));
        assertFalse(SourceReadService.isReactReleasesEndpoint(row));
        assertFalse(row.toString().contains("react.dev"));
        assertEquals(1, reads.internalPage(1, 10).total());
    }

    @Test void actualEndpointNeedsIndependentProductionConfigAndValidWindow() {
        insert("fixture:releases", "Fixture", "enabled",
                "{\"url\":\"https://api.github.com/repos/facebook/react/releases?per_page=30\"}",
                "production", "{\"adapter\":\"releases\"}", 2);
        var row = reads.internalSource("fixture:releases").orElseThrow();
        assertTrue(SourceReadService.isReactReleasesEndpoint(row));
        assertTrue(reads.productionEligible(row));
        insert("fixture:old", "Old", "enabled", "{\"url\":\"https://example.invalid/rss\"}",
                "production", null, 0);
        assertFalse(reads.productionEligible(reads.internalSource("fixture:old").orElseThrow()));
    }

    private void insert(String id, String name, String state, String config,
                        String stage, String effective, long version) {
        jdbc.update("INSERT INTO sources(id,source_kind,name,state,config_json,draft_config_json,"
                        + "effective_config_json,revision,config_revision,activation_stage,"
                        + "config_valid_until,category) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "article", name, state, config, null, effective, 1, version, stage,
                Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")), null);
    }
}
