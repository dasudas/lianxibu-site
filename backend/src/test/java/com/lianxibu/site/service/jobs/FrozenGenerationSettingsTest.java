package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenGenerationSettingsTest {
    @Test void copiedValuesAndSemanticHashAreIndependentOfRevisionAndOrdering() {
        var words = new ArrayList<>(List.of("fictional-b", "fictional-a"));
        var settings = new FrozenGenerationSettings(3, words, List.of("fictional-block"));
        words.clear();
        assertEquals(List.of("fictional-b", "fictional-a"), settings.followKeywords());
        assertThrows(UnsupportedOperationException.class, () -> settings.followKeywords().clear());
        var reordered = new FrozenGenerationSettings(7, List.of("fictional-a", "fictional-b"), List.of("fictional-block"));
        assertEquals(settings.semanticHash(), reordered.semanticHash());
        assertNotEquals(settings.toJson(), reordered.toJson());
        assertNotEquals(settings.semanticHash(), new FrozenGenerationSettings(3, List.of("fictional-new"), List.of("fictional-block")).semanticHash());
        var parsed = FrozenGenerationSettings.read(settings.toJson());
        assertEquals(settings, parsed);
        ((ObjectNode) settings.toJson()).put("settings_revision", 100);
        assertEquals(3, settings.revision());
    }

    @Test void rejectsUnknownFieldsInvalidKeywordsAndUnsafeRevisions() {
        var valid = new FrozenGenerationSettings(0, List.of(), List.of()).toJson();
        var extra = (ObjectNode) CanonicalJson.parse(CanonicalJson.bytes(valid));
        extra.put("api_key", "fictional-forbidden");
        assertThrows(IllegalArgumentException.class, () -> FrozenGenerationSettings.read(extra));
        assertThrows(IllegalArgumentException.class, () -> new FrozenGenerationSettings(-1, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FrozenGenerationSettings(CanonicalJson.MAX_SAFE_INTEGER + 1, List.of(), List.of()));
        for (var words : List.of(List.of(""), List.of("duplicate", "duplicate"), List.of("x".repeat(129)), Collections.nCopies(101, "word")))
            assertThrows(IllegalArgumentException.class, () -> new FrozenGenerationSettings(1, words, List.of()));
        ((ObjectNode) valid).put("settings_revision", "1");
        assertThrows(IllegalArgumentException.class, () -> FrozenGenerationSettings.read(valid));
    }
}
