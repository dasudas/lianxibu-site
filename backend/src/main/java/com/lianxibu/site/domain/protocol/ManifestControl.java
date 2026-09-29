package com.lianxibu.site.domain.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Frozen manifest business data is separate from the current lease attempt. */
public record ManifestControl(String runId, long partNo, String partHash,
                              JsonNode business, JsonNode attempt) {
    private static final Set<String> TOP = Set.of("business", "attempt");
    private static final Set<String> BUSINESS = Set.of("run_id", "part_no", "part_hash", "members");
    private static final Set<String> MEMBER = Set.of("member_id", "source_item_id",
            "observation_id", "payload_hash", "replaces_observation_id");
    private static final Set<String> ATTEMPT = Set.of("claim_token", "expected_run_revision");

    public static ManifestControl fromJson(JsonNode value, String pathRunId) {
        WireValues.object(value, TOP, TOP);
        JsonNode business = WireValues.object(value.get("business"), BUSINESS, BUSINESS);
        String runId = WireValues.id(business.get("run_id"), false);
        if (!runId.equals(pathRunId)) throw new IllegalArgumentException("target_mismatch");
        long partNo = WireValues.nonnegativeInteger(business.get("part_no"));
        String partHash = WireValues.sha256(business.get("part_hash"));
        JsonNode members = business.get("members");
        if (!members.isArray() || members.size() > 100) {
            throw new IllegalArgumentException("manifest part member limit");
        }
        List<JsonNode> sorted = new ArrayList<>();
        Set<String> memberIds = new HashSet<>();
        for (JsonNode member : members) {
            WireValues.object(member, MEMBER, MEMBER);
            String memberId = WireValues.id(member.get("member_id"), false);
            if (!memberIds.add(memberId)) throw new IllegalArgumentException("duplicate member ID");
            JsonNode sourceItemId = member.get("source_item_id");
            if (!sourceItemId.isNull()) WireValues.text(sourceItemId);
            for (String field : List.of("observation_id", "replaces_observation_id")) {
                JsonNode identity = member.get(field);
                if (!identity.isNull()) WireValues.id(identity, false);
            }
            JsonNode payloadHash = member.get("payload_hash");
            if (!payloadHash.isNull()) WireValues.sha256(payloadHash);
            sorted.add(member);
        }
        sorted.sort((left, right) -> CanonicalJson.compareScalars(
                left.get("member_id").textValue(), right.get("member_id").textValue()));
        ObjectNode part = JsonMapper.shared().createObjectNode();
        part.put("run_id", runId);
        part.put("part_no", partNo);
        ArrayNode orderedMembers = part.putArray("members");
        sorted.forEach(member -> orderedMembers.add(member.deepCopy()));
        if (CanonicalJson.bytes(part).length > 128 * 1024 ||
                !partHash.equals(CanonicalJson.hash(CanonicalJson.Domain.MANIFEST_PART, part))) {
            throw new IllegalArgumentException("manifest part hash or size mismatch");
        }
        JsonNode attempt = WireValues.object(value.get("attempt"), ATTEMPT, ATTEMPT);
        WireValues.id(attempt.get("claim_token"), false);
        JsonNode revision = attempt.get("expected_run_revision");
        if (!revision.isNull()) WireValues.nonnegativeInteger(revision);
        ObjectNode normalizedBusiness = ((ObjectNode) business).deepCopy();
        normalizedBusiness.set("members", orderedMembers.deepCopy());
        return new ManifestControl(runId, partNo, partHash, normalizedBusiness, attempt.deepCopy());
    }

    public String businessHash() {
        ObjectNode input = JsonMapper.shared().createObjectNode();
        input.put("operation", "manifest");
        input.set("business", business.deepCopy());
        return CanonicalJson.hash(CanonicalJson.Domain.CONTROL_BUSINESS, input);
    }

    public String attemptHash(String nodeId) {
        ObjectNode input = JsonMapper.shared().createObjectNode();
        input.put("node_id", WireValues.id(nodeId, false));
        input.put("operation", "manifest");
        input.put("business_hash", businessHash());
        input.set("attempt", attempt.deepCopy());
        return CanonicalJson.hash(CanonicalJson.Domain.CONTROL_ATTEMPT, input);
    }
}
