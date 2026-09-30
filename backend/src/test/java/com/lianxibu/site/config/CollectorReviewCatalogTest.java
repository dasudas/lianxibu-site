package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectorReviewCatalogTest {
    @TempDir Path directory;

    @Test void loadsOnlyExplicitHostApprovalsAndRejectsUnknownFields() throws Exception {
        Path file = directory.resolve("reviews.json");
        String catalog = "{\"sources\":[{\"source_id\":\"fixture\",\"source_kind\":\"rss\","
                + "\"config_sha256\":\"" + "a".repeat(64) + "\","
                + "\"entry_url\":\"https://example.invalid/feed\",\"category\":\"news_tech\"}],"
                + "\"articles\":[],\"order_rules\":[{\"source_id\":\"fixture\","
                + "\"parser_version\":\"v1\",\"basis\":\"sequence\",\"domain\":\"fixture\"}]}";
        Files.writeString(file, catalog);
        var loaded = CollectorReviewCatalog.load(file.toString());
        assertEquals(1, loaded.sources().size());
        assertEquals(0, loaded.articles().size());
        assertEquals(1, loaded.orderRules().size());
        assertFalse(loaded.toString().contains("example.invalid"));
        Files.writeString(file, catalog.replace("\"articles\":[]", "\"unreviewed\":true,\"articles\":[]"));
        assertThrows(IllegalStateException.class, () -> CollectorReviewCatalog.load(file.toString()));
        assertTrue(CollectorReviewCatalog.load("").sources().isEmpty());
        assertThrows(IllegalStateException.class, () -> CollectorReviewCatalog.load("relative.json"));
    }
}
