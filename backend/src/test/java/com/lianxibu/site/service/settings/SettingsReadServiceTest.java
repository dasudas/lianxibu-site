package com.lianxibu.site.service.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.settings.JdbcSettingsReads;
import java.sql.Timestamp;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SettingsReadServiceTest {
    private JdbcTemplate jdbc;
    private SettingsReadService reads;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:settings-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,"
                + "value_json CLOB,revision BIGINT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,state VARCHAR(16))");
        reads = new SettingsReadService(new JdbcSettingsReads(source),
                new DataSourceTransactionManager(source));
    }

    @Test void absentFieldsAreExplicitAndLegacyRawTermsStayUnconverted() {
        put("preferences", "{\"pause_new_jobs\":1,\"watch_terms_raw\":\"AI,ML\","
                + "\"blocked_terms_raw\":\"spam|ads\"}");
        put("legacy_usage_total", "{\"tokens\":8}");
        var value = reads.current();
        assertEquals(1, value.revision());
        assertTrue(value.aiPaused());
        assertEquals("AI,ML", value.legacyWatchTermsRaw());
        assertTrue(value.missingFields().contains("follow_keywords_unmapped"));
        assertTrue(value.missingFields().contains("blocked_keywords_unmapped"));
        assertTrue(value.missingFields().contains("default_package_id"));
        assertTrue(value.missingFields().contains("operational_limits"));
        assertFalse(value.toString().contains("AI,ML"));
    }

    @Test void invalidDefaultNeverOverridesExplicitPublishedPackage() {
        put("preferences", "{\"default_package_id\":\"draft-package\"}");
        jdbc.update("INSERT INTO learning_packages VALUES (?,?)", "draft-package", "draft");
        jdbc.update("INSERT INTO learning_packages VALUES (?,?)", "published-package", "published");
        assertTrue(reads.resolvePublishedPackage(null).isEmpty());
        assertEquals("published-package", reads.resolvePublishedPackage("published-package").orElseThrow());
        assertTrue(reads.resolvePublishedPackage("draft-package").isEmpty());
    }

    @Test void missingPreferencesHasExplicitMissingState() {
        var value = reads.current();
        assertEquals(0, value.revision());
        assertNull(value.aiPaused());
        assertTrue(value.missingFields().contains("preferences"));
        assertTrue(reads.resolvePublishedPackage(null).isEmpty());
    }

    private void put(String key, String json) {
        jdbc.update("INSERT INTO runtime_settings VALUES (?,?,?,?)", key, json, 1,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    }
}
