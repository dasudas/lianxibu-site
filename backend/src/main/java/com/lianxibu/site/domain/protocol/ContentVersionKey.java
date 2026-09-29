package com.lianxibu.site.domain.protocol;

import java.util.Set;

import tools.jackson.databind.JsonNode;

/** A qualified content version cannot be identified by id alone. */
public record ContentVersionKey(String contentKind, String contentId, long revision,
                                String bodyHash, String inputJobId) {
    private static final Set<String> FIELDS = Set.of(
            "kind", "content_kind", "content_id", "revision", "body_hash", "input_job_id");

    public static ContentVersionKey fromJson(JsonNode value) {
        WireValues.object(value, FIELDS, FIELDS);
        if (!"content_version".equals(WireValues.text(value.get("kind")))) {
            throw new IllegalArgumentException("content_version kind required");
        }
        String contentKind = WireValues.id(value.get("content_kind"), false);
        String contentId = WireValues.id(value.get("content_id"), false);
        long revision = WireValues.nonnegativeInteger(value.get("revision"));
        String bodyHash = WireValues.sha256(value.get("body_hash"));
        JsonNode inputJobNode = value.get("input_job_id");
        String inputJobId = inputJobNode.isNull() ? null : WireValues.id(inputJobNode, false);
        return new ContentVersionKey(contentKind, contentId, revision, bodyHash, inputJobId);
    }

    public void requirePathIdentity(String pathKind, String pathId) {
        if (!contentKind.equals(pathKind) || !contentId.equals(pathId)) {
            throw new IllegalArgumentException("target_mismatch");
        }
    }
}
