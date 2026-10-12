package com.lianxibu.site.domain.protocol;

import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Immutable wire observation identity and its verified frozen payload. */
public record ObservationEnvelope(String observationId, String memberId, String runId,
                                  String sourceId, String sourceItemId, String kind,
                                  String payloadHash, JsonNode wire) {
    private static final Set<String> FIELDS = Set.of(
            "protocol_revision", "observation_id", "member_id", "run_id",
            "business_date", "source_id", "source_revision", "source_item_id",
            "kind", "retrieved_at", "replaces_observation_id", "payload",
            "payload_hash");
    private static final Set<String> ARTICLE_FIELDS = Set.of("title", "source_url",
            "published_at", "updated_at", "time_basis", "source_summary", "body_text",
            "content_status", "body_truncated", "parser_version", "config_version", "source_order");
    private static final Set<String> ARTICLE_FIELDS_V012 = java.util.stream.Stream.concat(
            ARTICLE_FIELDS.stream(), java.util.stream.Stream.of("submitted_at"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> CATALOG_FIELDS = Set.of("schema_version", "parser_version",
            "config_version", "category", "identity", "title", "source_url", "version",
            "published_at", "updated_at", "summary", "document_text", "truncated", "evidence");
    private static final Set<String> METRIC_FIELDS = Set.of("schema_version", "parser_version",
            "config_version", "subject", "benchmark", "benchmark_version", "task", "score",
            "unit", "scale_min", "scale_max", "higher_is_better", "evaluated_at",
            "evaluation_scope", "evaluation_config", "provenance", "evidence");
    private static final Set<String> SOURCE_ORDER_FIELDS = Set.of("basis", "domain", "value", "evidence_ref");

    public static ObservationEnvelope fromJson(JsonNode value) {
        WireValues.object(value, FIELDS, FIELDS);
        String protocol = WireValues.text(value.get("protocol_revision"));
        if (!Set.of("0.11", "0.12").contains(protocol)) {
            throw new IllegalArgumentException("schema_not_supported");
        }
        String observationId = WireValues.id(value.get("observation_id"), false);
        String memberId = WireValues.id(value.get("member_id"), false);
        String runId = WireValues.id(value.get("run_id"), false);
        WireValues.businessDate(value.get("business_date"));
        String sourceId = WireValues.id(value.get("source_id"), true);
        WireValues.nonnegativeInteger(value.get("source_revision"));
        String sourceItemId = WireValues.text(value.get("source_item_id"));
        if (sourceItemId.isEmpty() || sourceItemId.codePointCount(0, sourceItemId.length()) > 4096) {
            throw new IllegalArgumentException("source item ID outside wire bounds");
        }
        String kind = WireValues.text(value.get("kind"));
        if (!Set.of("article", "catalog", "metric").contains(kind)) {
            throw new IllegalArgumentException("unknown observation kind");
        }
        WireValues.utcMicroseconds(value.get("retrieved_at"));
        JsonNode replaces = value.get("replaces_observation_id");
        if (!replaces.isNull()) WireValues.id(replaces, false);
        JsonNode payload = value.get("payload");
        validatePayloadShape(protocol,kind,payload);
        String payloadHash = WireValues.sha256(value.get("payload_hash"));
        ObjectNode unhashed = ((ObjectNode) value).deepCopy();
        unhashed.remove("payload_hash");
        if (!payloadHash.equals(CanonicalJson.hash(CanonicalJson.Domain.OBSERVATION, unhashed))) {
            throw new IllegalArgumentException("observation payload hash mismatch");
        }
        return new ObservationEnvelope(observationId, memberId, runId, sourceId,
                sourceItemId, kind, payloadHash, value.deepCopy());
    }

    /** Schema validation shared by the wire envelope and the already retained payload. */
    public static void validatePayloadShape(String protocol,String kind,JsonNode payload) {
        if(!Set.of("0.11","0.12").contains(protocol))throw new IllegalArgumentException("schema_not_supported");
        Set<String> payloadFields = switch (kind) {
            case "article" -> "0.12".equals(protocol) ? ARTICLE_FIELDS_V012 : ARTICLE_FIELDS;
            case "catalog" -> CATALOG_FIELDS;
            case "metric" -> METRIC_FIELDS;
            default -> throw new IllegalArgumentException("unknown observation kind");
        };
        WireValues.object(payload, payloadFields, payloadFields);
        if (kind.equals("article")) {
            WireValues.object(payload.get("source_order"), SOURCE_ORDER_FIELDS, SOURCE_ORDER_FIELDS);
        } else if (!"1".equals(WireValues.text(payload.get("schema_version")))) {
            throw new IllegalArgumentException("schema_not_supported");
        }
    }
}
