package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.jobs.GenerationFlowService.Dependency;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Complete dependency inventory bound to the immutable input identity. */
public final class FrozenDependencyManifest {
    public static final String FIELD = "dependency_manifest_v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private FrozenDependencyManifest() { }
    public record Manifest(List<Dependency> dependencies, List<String> expectedIds) {
        public Manifest { dependencies = List.copyOf(dependencies); expectedIds = List.copyOf(expectedIds); }
    }
    public static JsonNode create(List<Dependency> dependencies, List<String> expectedIds) {
        if (dependencies == null || expectedIds == null || dependencies.size() > 10000 || expectedIds.size() > 10000) throw invalid();
        var sorted = new TreeMap<String, Dependency>();
        for (var dep : dependencies) {
            if (dep == null || dep.stage() == null || dep.state() == null || dep.state().isBlank() || dep.state().length() > 80) throw invalid();
            if (sorted.put(id(dep.id()), dep) != null) throw invalid();
        }
        var expected = new TreeSet<String>();
        for (String key : expectedIds) if (!expected.add(id(key))) throw invalid();
        var result = JSON.createObjectNode(); result.put("schema_version", FIELD);
        var items = result.putArray("dependencies");
        for (var dep : sorted.values()) {
            var item = items.addObject(); item.put("id", dep.id()); item.put("stage", dep.stage().name()); item.put("state", dep.state());
            if (dep.completedAt() == null) item.putNull("completed_at"); else item.put("completed_at", dep.completedAt().toString());
        }
        var ids = result.putArray("expected_ids"); expected.forEach(ids::add);
        if (CanonicalJson.bytes(result).length > 262144) throw invalid();
        return result;
    }
    public static Manifest read(JsonNode node) {
        try {
            fields(node, Set.of("schema_version", "dependencies", "expected_ids"));
            if (!FIELD.equals(text(node, "schema_version")) || !node.get("dependencies").isArray() || !node.get("expected_ids").isArray()) throw invalid();
            if (node.get("dependencies").size() > 10000 || node.get("expected_ids").size() > 10000 || CanonicalJson.bytes(node).length > 262144) throw invalid();
            var dependencies = new ArrayList<Dependency>(); var expected = new ArrayList<String>();
            for (var item : node.get("dependencies")) {
                fields(item, Set.of("id", "stage", "state", "completed_at"));
                dependencies.add(new Dependency(text(item, "id"), Stage.valueOf(text(item, "stage")), text(item, "state"), item.get("completed_at").isNull() ? null : Instant.parse(text(item, "completed_at"))));
            }
            for (var item : node.get("expected_ids")) { if (!item.isTextual()) throw invalid(); expected.add(item.asText()); }
            if (!Arrays.equals(CanonicalJson.bytes(node), CanonicalJson.bytes(create(dependencies, expected)))) throw invalid();
            return new Manifest(dependencies, expected);
        } catch (RuntimeException bad) { throw invalid(); }
    }
    private static String id(String value) { try { return WireValues.id(value, false); } catch (RuntimeException bad) { throw invalid(); } }
    private static String text(JsonNode node, String key) { if (!node.get(key).isTextual()) throw invalid(); return node.get(key).asText(); }
    private static void fields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size() || node.properties().stream().anyMatch(p -> !fields.contains(p.getKey()))) throw invalid();
    }
    private static IllegalStateException invalid() { return new IllegalStateException("invalid frozen dependency manifest"); }
}
