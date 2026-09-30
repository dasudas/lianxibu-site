package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Bounded normalized evidence checks before the shared write transaction. */
public final class ObservationPayloadPolicy {
    private static final Set<String> CATEGORIES = Set.of("agent", "skill", "workflow", "model");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("url", "revision", "path",
            "file_hash", "evidence_type");
    private static final Set<String> IDENTITY_FIELDS = Set.of("namespace", "key");
    private static final Set<String> CONFIG_FIELDS = Set.of("agent", "model", "skills", "parameters");
    private static final Set<String> EVIDENCE_TYPES = Set.of("metadata", "readme", "release",
            "skill_document", "author_report", "benchmark");
    private static final Set<String> UNITS = Set.of("percent", "ratio", "points", "seconds", "count");
    private ObservationPayloadPolicy() { }

    public static void validate(String kind, JsonNode payload) {
        if (CanonicalJson.bytes(payload).length > 200 * 1024) invalid();
        switch (kind) {
            case "article" -> article(payload);
            case "catalog" -> catalog(payload);
            case "metric" -> metric(payload);
            default -> invalid();
        }
    }

    private static void article(JsonNode value) {
        String title = required(value.get("title"), 512);
        if (title.length() > 512) invalid();
        https(value.get("source_url"));
        utc(value.get("published_at"));
        utc(value.get("updated_at"));
        oneOf(value.get("time_basis"), Set.of("published", "updated", "unknown"));
        String summary = optional(value.get("source_summary"), 16384);
        String body = optional(value.get("body_text"), 50000);
        if (summary != null && summary.length() > 16384
                || body != null && body.length() > 50000) invalid();
        if (!value.get("body_text").isNull()
                && value.get("body_text").asText().getBytes(StandardCharsets.UTF_8).length > 204800) invalid();
        oneOf(value.get("content_status"), Set.of("full", "partial", "unavailable"));
        bool(value.get("body_truncated"));
        parser(value);
        JsonNode order = value.get("source_order");
        Set<String> keys = Set.of("basis", "domain", "value", "evidence_ref");
        WireValues.object(order, keys, keys);
        oneOf(order.get("basis"), Set.of("unknown", "sequence", "revision_time"));
        if ("unknown".equals(order.get("basis").asText())) {
            if (!order.get("domain").isNull() || !order.get("value").isNull()
                    || !order.get("evidence_ref").isNull()) invalid();
        } else {
            required(order.get("domain"), 128);
            required(order.get("value"), 256);
            required(order.get("evidence_ref"), 256);
        }
    }

    private static void catalog(JsonNode value) {
        String category = oneOf(value.get("category"), CATEGORIES);
        identity(value.get("identity"), category);
        required(value.get("title"), 512);
        https(value.get("source_url"));
        optional(value.get("version"), 256);
        utc(value.get("published_at"));
        utc(value.get("updated_at"));
        String summary = optional(value.get("summary"), 16384);
        String document = optional(value.get("document_text"), 131072);
        bool(value.get("truncated"));
        evidence(value.get("evidence"));
        int narrativeBytes = (summary == null ? 0 : summary.getBytes(StandardCharsets.UTF_8).length)
                + (document == null ? 0 : document.getBytes(StandardCharsets.UTF_8).length)
                + CanonicalJson.bytes(value.get("evidence")).length;
        if (narrativeBytes > 128 * 1024) invalid();
        parser(value);
    }

    private static void metric(JsonNode value) {
        JsonNode subject = value.get("subject");
        Set<String> fields = Set.of("category", "identity");
        WireValues.object(subject, fields, fields);
        String category = oneOf(subject.get("category"), CATEGORIES);
        identity(subject.get("identity"), category);
        required(value.get("benchmark"), 128);
        required(value.get("benchmark_version"), 256);
        required(value.get("task"), 256);
        BigDecimal score = decimal(value.get("score"));
        String unit = oneOf(value.get("unit"), UNITS);
        BigDecimal min = decimal(value.get("scale_min"));
        BigDecimal max = decimal(value.get("scale_max"));
        if (min != null && max != null && min.compareTo(max) > 0) invalid();
        if (score != null && (min != null && score.compareTo(min) < 0
                || max != null && score.compareTo(max) > 0)) invalid();
        if ("percent".equals(unit) && (min == null || max == null
                || min.compareTo(BigDecimal.ZERO) != 0
                || max.compareTo(BigDecimal.valueOf(100)) != 0)) invalid();
        if ("ratio".equals(unit) && (min == null || max == null
                || min.compareTo(BigDecimal.ZERO) != 0
                || max.compareTo(BigDecimal.ONE) != 0)) invalid();
        if ("count".equals(unit) && score != null
                && (score.signum() < 0 || score.scale() > 0)) invalid();
        if ("seconds".equals(unit) && score != null && score.signum() < 0) invalid();
        bool(value.get("higher_is_better"));
        utc(value.get("evaluated_at"));
        String scope = oneOf(value.get("evaluation_scope"), Set.of("entity", "configuration"));
        JsonNode config = value.get("evaluation_config");
        WireValues.object(config, CONFIG_FIELDS, CONFIG_FIELDS);
        String agent = optional(config.get("agent"), 256);
        String model = optional(config.get("model"), 256);
        if ("configuration".equals(scope) && agent == null && model == null) invalid();
        JsonNode skills = config.get("skills");
        if (!skills.isArray() || skills.size() > 100) invalid();
        Set<String> seen = new HashSet<>();
        for (JsonNode skill : skills) if (!seen.add(required(skill, 256))) invalid();
        JsonNode parameters = config.get("parameters");
        if (!parameters.isObject() || parameters.size() > 32) invalid();
        parameters.properties().forEach(entry -> {
            if (entry.getKey().length() > 64) invalid();
            if (WireValues.text(entry.getValue()).codePointCount(0,
                    entry.getValue().asText().length()) > 256) invalid();
        });
        oneOf(value.get("provenance"), Set.of("author_self_report", "third_party"));
        evidence(value.get("evidence"));
        parser(value);
    }

    private static void identity(JsonNode value, String category) {
        WireValues.object(value, IDENTITY_FIELDS, IDENTITY_FIELDS);
        String namespace = oneOf(value.get("namespace"), Set.of("github_repository",
                "github_skill", "model_registry"));
        String key = required(value.get("key"), 4096);
        if (key.getBytes(StandardCharsets.UTF_8).length > 4096) invalid();
        if (("skill".equals(category) && !"github_skill".equals(namespace))
                || ("model".equals(category) && !"model_registry".equals(namespace))
                || (Set.of("agent", "workflow").contains(category)
                    && !"github_repository".equals(namespace))) invalid();
    }

    private static void evidence(JsonNode value) {
        if (!value.isArray() || value.size() < 1 || value.size() > 32) invalid();
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : value) {
            WireValues.object(entry, EVIDENCE_FIELDS, EVIDENCE_FIELDS);
            String url = https(entry.get("url"));
            String revision = optional(entry.get("revision"), 256);
            String path = optional(entry.get("path"), 1024);
            if (entry.get("file_hash") != null && !entry.get("file_hash").isNull()) {
                WireValues.sha256(entry.get("file_hash"));
            }
            String kind = oneOf(entry.get("evidence_type"), EVIDENCE_TYPES);
            if (Set.of("readme", "skill_document").contains(kind)
                    && (revision == null || path == null)) invalid();
            if (!seen.add(url + "\u0000" + revision + "\u0000" + path)) invalid();
        }
    }

    private static String parser(JsonNode value) {
        String parser = required(value.get("parser_version"), 64);
        if (parser.length() > 64) invalid();
        WireValues.nonnegativeInteger(value.get("config_version"));
        return parser;
    }
    private static String required(JsonNode node, int max) {
        String text = WireValues.text(node);
        if (text.isBlank() || text.codePointCount(0, text.length()) > max) invalid();
        return text;
    }
    private static String optional(JsonNode node, int max) {
        if (node == null) { invalid(); }
        if (node.isNull()) return null;
        String text = WireValues.text(node);
        if (text.codePointCount(0, text.length()) > max) invalid();
        return text;
    }
    private static String https(JsonNode node) {
        String text = required(node, 2000);
        if (text.length() > 2000) invalid();
        try {
            URI uri = URI.create(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) invalid();
        } catch (IllegalArgumentException malformed) { invalid(); }
        return text;
    }
    private static void utc(JsonNode node) {
        if (node == null) invalid();
        if (!node.isNull()) WireValues.utcMicroseconds(node);
    }
    private static void bool(JsonNode node) {
        if (node == null || !node.isBoolean()) invalid();
    }
    private static String oneOf(JsonNode node, Set<String> values) {
        String text = WireValues.text(node);
        if (!values.contains(text)) invalid();
        return text;
    }
    private static BigDecimal decimal(JsonNode node) {
        if (node == null) { invalid(); }
        if (node.isNull()) return null;
        String text = WireValues.text(node);
        if (!text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,8})?")
                || text.replaceAll("[^0-9]", "").length() > 20) invalid();
        return new BigDecimal(text);
    }
    private static void invalid() { throw new IllegalArgumentException("invalid observation payload"); }
}
