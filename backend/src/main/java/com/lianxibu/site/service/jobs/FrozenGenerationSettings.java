package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Captured content preferences. Pause and operational policy remain dispatch-time controls. */
public record FrozenGenerationSettings(long revision, List<String> followKeywords, List<String> blockedKeywords) {
    public static final String FIELD = "generation_settings_v1";
    private static final String VERSION = "generation-settings-v1";
    private static final Set<String> FIELDS = Set.of("schema_version", "settings_revision", "follow_keywords", "blocked_keywords");

    public FrozenGenerationSettings {
        if (revision < 0 || revision > CanonicalJson.MAX_SAFE_INTEGER) throw invalid();
        followKeywords = keywords(followKeywords);
        blockedKeywords = keywords(blockedKeywords);
    }

    public JsonNode toJson() {
        var value = values(followKeywords, blockedKeywords);
        value.put("settings_revision", revision);
        return value;
    }

    /** Only a material preference change affects eligibility; reordering is not a change. */
    public String semanticHash() {
        return CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,
                values(followKeywords.stream().sorted().toList(), blockedKeywords.stream().sorted().toList()));
    }

    public static FrozenGenerationSettings read(JsonNode value) {
        WireValues.object(value, FIELDS, FIELDS);
        if (!VERSION.equals(WireValues.text(value.get("schema_version")))) throw invalid();
        return new FrozenGenerationSettings(WireValues.nonnegativeInteger(value.get("settings_revision")),
                readKeywords(value.get("follow_keywords")), readKeywords(value.get("blocked_keywords")));
    }

    private static ObjectNode values(List<String> follow, List<String> blocked) {
        var value = JsonMapper.shared().createObjectNode();
        value.put("schema_version", VERSION);
        var a = value.putArray("follow_keywords"); follow.forEach(a::add);
        var b = value.putArray("blocked_keywords"); blocked.forEach(b::add);
        return value;
    }
    private static List<String> readKeywords(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > 100) throw invalid();
        var result = new ArrayList<String>();
        for (var word : value) result.add(WireValues.text(word));
        return result;
    }
    private static List<String> keywords(List<String> values) {
        if (values == null || values.size() > 100 || values.stream().anyMatch(v -> v == null || v.isBlank()
                || v.codePointCount(0, v.length()) > 128) || new HashSet<>(values).size() != values.size()) throw invalid();
        return List.copyOf(values);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("frozen generation settings invalid"); }
}
