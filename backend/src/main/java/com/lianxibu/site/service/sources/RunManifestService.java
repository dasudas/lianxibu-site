package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.sources.JdbcRunManifestStore;
import com.lianxibu.site.repository.sources.JdbcRunManifestStore.Run;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Freezes a complete transfer manifest and derives terminal state from its actual members. */
public final class RunManifestService {
    public interface NodeAuthorizer {
        Principal authorize(String bearer, String sourceId);
    }
    public record Member(String memberId, String sourceItemId, String observationId,
                         String payloadHash, String replacesObservationId) { }
    public record Part(String runId, long partNo, String partHash, List<Member> members) { }
    public record Result(String runId, String manifestHash, long partCount, long expectedCount,
                         String fetchOutcome, String coverageJson) { }
    public record Outcome(String runId, String deliveryState, long accepted, long excluded,
                          long pending, long revision) { }
    public static final class RunFrozen extends RuntimeException {
        public RunFrozen() { super("run manifest identity or terminal state is frozen"); }
    }
    private static final Set<String> FINAL = Set.of("complete", "partial", "no_usable_input", "empty_success");
    private static final Set<String> FETCH = Set.of("succeeded", "failed", "partial");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcRunManifestStore store;
    private final SharedWriteExecutor writes;
    private final NodeAuthorizer nodes;
    private final Clock clock;

    public RunManifestService(JdbcRunManifestStore store, SharedWriteExecutor writes,
                              NodeAuthorizer nodes, Clock clock) {
        if (store == null || writes == null || nodes == null || clock == null)
            throw new IllegalArgumentException("manifest dependencies required");
        this.store = store;
        this.writes = writes;
        this.nodes = nodes;
        this.clock = clock;
    }
    public RunManifestService(JdbcRunManifestStore store, SharedWriteExecutor writes,
                              NodeIdentityService nodes, Clock clock) {
        this(store, writes, (bearer, sourceId) -> nodes.authorizeBearer(bearer, "run:write", sourceId), clock);
    }

    public Outcome putPart(String bearer, Part part, String claimToken, ActionContext action) {
        validatePart(part);
        if (!partHash(part.runId(), part.partNo(), part.members()).equals(part.partHash())) throw new RunFrozen();
        String businessHash = controlHash("manifest", partJson(part));
        Run first = required(part.runId());
        Principal initial = nodes.authorize(bearer, first.sourceId());
        String scope = "manifest:" + part.runId() + ":" + part.partNo();
        byte[] attempt = attemptHash(initial.nodeId(), "manifest", businessHash, claimToken, null);
        return writes.execute(action, ignored -> {
            nodes.authorize(bearer, required(part.runId()).sourceId());
            var receipt = store.receipt(initial.nodeId(), scope, action.actionKey());
            if (receipt == null) return null;
            if (!MessageDigest.isEqual(receipt.attemptHash(), attempt)
                    || !MessageDigest.isEqual(receipt.businessHash(), hex(businessHash))) throw new RunFrozen();
            return outcome(required(part.runId()));
        }, context -> {
            Run run = requiredLocked(part.runId());
            Principal current = nodes.authorize(bearer, run.sourceId());
            var frozen = store.fixed(scope);
            if (frozen != null) {
                if (!MessageDigest.isEqual(frozen.businessHash(), hex(businessHash))) throw new RunFrozen();
                store.receipt(current.nodeId(), scope, action.actionKey());
                return outcome(run);
            }
            if (FINAL.contains(run.deliveryState()) || run.resultPartCount() != null
                    && part.partNo() >= run.resultPartCount()) throw new RunFrozen();
            requireLease(run, current.nodeId(), claimToken);
            if (run.partCount() == null) store.markManifestStarted(run.id());
            for (Member member : part.members()) {
                store.attachMember(run, member.memberId(), member.sourceItemId(),
                        member.observationId(), member.payloadHash(), member.replacesObservationId(),
                        part.partNo(), current.nodeId(), clock.instant());
            }
            store.saveReceipt(current.nodeId(), run.id(), scope, action.actionKey(), attempt,
                    hex(businessHash), partJson(part), clock.instant());
            recompute(run.id());
            return outcome(requiredLocked(run.id()));
        });
    }

    public Outcome submitResult(String bearer, Result result, String claimToken,
                                long expectedRevision, ActionContext action) {
        validateResult(result);
        String businessJson = resultJson(result);
        String businessHash = controlHash("result", businessJson);
        Run first = required(result.runId());
        Principal initial = nodes.authorize(bearer, first.sourceId());
        String scope = "result:" + result.runId();
        byte[] attempt = attemptHash(initial.nodeId(), "result", businessHash, claimToken, expectedRevision);
        return writes.execute(action, ignored -> {
            nodes.authorize(bearer, required(result.runId()).sourceId());
            var receipt = store.receipt(initial.nodeId(), scope, action.actionKey());
            if (receipt == null) return null;
            if (!MessageDigest.isEqual(receipt.attemptHash(), attempt)
                    || !MessageDigest.isEqual(receipt.businessHash(), hex(businessHash))) throw new RunFrozen();
            return outcome(required(result.runId()));
        }, context -> {
            Run run = requiredLocked(result.runId());
            Principal current = nodes.authorize(bearer, run.sourceId());
            var frozen = store.fixed(scope);
            if (frozen != null) {
                if (!MessageDigest.isEqual(frozen.businessHash(), hex(businessHash))) throw new RunFrozen();
                return outcome(run);
            }
            if (FINAL.contains(run.deliveryState())) throw new RunFrozen();
            requireLease(run, current.nodeId(), claimToken);
            if (run.revision() != expectedRevision) throw new RunFrozen();
            if (run.resultJson() != null || run.partCount() != null && run.partCount() > result.partCount())
                throw new RunFrozen();
            store.saveResult(result.runId(), hex(result.manifestHash()), result.partCount(),
                    result.expectedCount(), result.fetchOutcome(), result.coverageJson(), businessJson);
            store.saveReceipt(current.nodeId(), result.runId(), scope, action.actionKey(), attempt,
                    hex(businessHash), businessJson, clock.instant());
            recompute(result.runId());
            return outcome(requiredLocked(result.runId()));
        });
    }

    /** An explicit exclusion is the only way to finish a member without an accepted ACK. */
    public Outcome sealMember(String bearer, String runId, String memberId, long expectedMemberRevision,
                              long expectedRunRevision,
                              String reason, String claimToken, ActionContext action) {
        if (!identity(runId) || !identity(memberId) || expectedMemberRevision < 0
                || reason == null || !reason.matches("[A-Za-z0-9._:-]{1,128}")) throw new RunFrozen();
        ObjectNode business = JSON.createObjectNode();
        business.put("run_id", runId);
        business.put("member_id", memberId);
        business.put("expected_member_revision", expectedMemberRevision);
        business.put("action", "seal");
        business.put("reason", reason);
        String businessJson = new String(CanonicalJson.bytes(business), StandardCharsets.UTF_8);
        String businessHash = controlHash("member_seal", businessJson);
        Run first = required(runId);
        Principal initial = nodes.authorize(bearer, first.sourceId());
        String scope = "member_seal:" + businessHash;
        byte[] attempt = attemptHash(initial.nodeId(), "member_seal", businessHash,
                claimToken, expectedRunRevision);
        return writes.execute(action, ignored -> {
            nodes.authorize(bearer, required(runId).sourceId());
            var receipt = store.receipt(initial.nodeId(), scope, action.actionKey());
            if (receipt == null) return null;
            if (!MessageDigest.isEqual(receipt.attemptHash(), attempt)
                    || !MessageDigest.isEqual(receipt.businessHash(), hex(businessHash))) throw new RunFrozen();
            return outcome(required(runId));
        }, context -> {
            Run run = requiredLocked(runId);
            Principal current = nodes.authorize(bearer, run.sourceId());
            if (FINAL.contains(run.deliveryState())) throw new RunFrozen();
            requireLease(run, current.nodeId(), claimToken);
            if (run.revision() != expectedRunRevision) throw new RunFrozen();
            if (!store.seal(runId, memberId, expectedMemberRevision, reason)) throw new RunFrozen();
            store.saveReceipt(current.nodeId(), runId, scope, action.actionKey(), attempt,
                    hex(businessHash), businessJson,
                    clock.instant());
            recompute(runId);
            return outcome(requiredLocked(runId));
        });
    }

    /** Called in the observation or seal write transaction, after the member changes. */
    public void recomputeAfterMember(String runId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("member reconciliation requires the observation write transaction");
        recompute(runId);
    }

    private void recompute(String runId) {
        Run run = requiredLocked(runId);
        if (run.resultJson() == null || FINAL.contains(run.deliveryState())) return;
        JsonNode result = CanonicalJson.parse(run.resultJson());
        long parts = result.get("part_count").longValue();
        long expected = result.get("expected_count").longValue();
        List<JdbcRunManifestStore.PartReceipt> uploaded = store.parts(runId);
        if (uploaded.size() > parts) throw new RunFrozen();
        ArrayNode descriptors = JSON.createArrayNode();
        long members = 0;
        boolean complete = true;
        for (int index = 0; index < uploaded.size(); index++) {
            var part = uploaded.get(index);
            if (part.partNo() != index) complete = false;
            ObjectNode descriptor = descriptors.addObject();
            descriptor.put("part_no", part.partNo());
            descriptor.put("part_hash", part.hash());
            descriptor.put("member_count", part.memberCount());
            members += part.memberCount();
        }
        complete = complete && uploaded.size() == parts;
        if (complete) {
            ObjectNode manifest = JSON.createObjectNode();
            manifest.put("run_id", runId);
            manifest.put("expected_count", expected);
            manifest.set("parts", descriptors);
            if (members != expected || !CanonicalJson.hash(Domain.MANIFEST, manifest)
                    .equals(result.get("manifest_hash").asText())) throw new RunFrozen();
        }
        var counts = store.counts(runId);
        if (counts.total() > expected || counts.accepted() + counts.excluded() + counts.pending() != counts.total())
            throw new RunFrozen();
        long pending = expected - counts.accepted() - counts.excluded();
        String state = "waiting_items";
        if (complete && pending == 0) {
            if (expected == 0) state = "succeeded".equals(result.get("fetch_outcome").asText())
                    ? "empty_success" : "no_usable_input";
            else if (counts.accepted() == 0) state = "no_usable_input";
            else state = counts.excluded() == 0 ? "complete" : "partial";
        }
        store.updateCounts(runId, state, counts.accepted(), counts.excluded(), pending,
                FINAL.contains(state) ? clock.instant() : null);
    }

    public static String partHash(String runId, long partNo, List<Member> members) {
        ObjectNode value = JSON.createObjectNode();
        value.put("run_id", runId);
        value.put("part_no", partNo);
        ArrayNode array = value.putArray("members");
        for (Member member : members) {
            ObjectNode row = array.addObject();
            row.put("member_id", member.memberId());
            nullable(row, "source_item_id", member.sourceItemId());
            row.put("observation_id", member.observationId());
            row.put("payload_hash", member.payloadHash());
            nullable(row, "replaces_observation_id", member.replacesObservationId());
        }
        return CanonicalJson.hash(Domain.MANIFEST_PART, value);
    }
    public static String manifestHash(String runId, long expected, List<Part> parts) {
        ObjectNode value = JSON.createObjectNode();
        value.put("run_id", runId);
        value.put("expected_count", expected);
        ArrayNode array = value.putArray("parts");
        for (Part part : parts) {
            ObjectNode row = array.addObject();
            row.put("part_no", part.partNo());
            row.put("part_hash", part.partHash());
            row.put("member_count", part.members().size());
        }
        return CanonicalJson.hash(Domain.MANIFEST, value);
    }

    private void validatePart(Part part) {
        if (part == null || !identity(part.runId())
                || part.partNo() < 0 || part.partNo() > CanonicalJson.MAX_SAFE_INTEGER
                || part.members() == null || part.members().size() > 100 || !hashText(part.partHash())) throw new RunFrozen();
        Set<String> ids = new HashSet<>();
        String previous = null;
        for (Member member : part.members()) {
            if (member == null || !identity(member.memberId()) || !identity(member.observationId())
                    || !hashText(member.payloadHash()) || !ids.add(member.memberId())
                    || member.sourceItemId() != null && (member.sourceItemId().isBlank()
                    || member.sourceItemId().getBytes(StandardCharsets.UTF_8).length > 4096)
                    || member.replacesObservationId() != null && !identity(member.replacesObservationId())
                    || previous != null && previous.compareTo(member.memberId()) >= 0) throw new RunFrozen();
            previous = member.memberId();
        }
        ObjectNode value = JSON.createObjectNode();
        value.put("run_id", part.runId());
        value.put("part_no", part.partNo());
        ArrayNode array = value.putArray("members");
        for (Member member : part.members()) array.add(CanonicalJson.parse(memberJson(member)));
        if (CanonicalJson.bytes(value).length > 128 * 1024) throw new RunFrozen();
    }
    private static void validateResult(Result value) {
        if (value == null || !identity(value.runId()) || !hashText(value.manifestHash())
                || value.partCount() < 0 || value.expectedCount() < 0
                || value.partCount() > CanonicalJson.MAX_SAFE_INTEGER
                || value.expectedCount() > CanonicalJson.MAX_SAFE_INTEGER
                || !FETCH.contains(value.fetchOutcome()) || value.coverageJson() == null
                || !CanonicalJson.parse(value.coverageJson()).isObject()) throw new RunFrozen();
    }
    private Run required(String id) {
        Run value = store.run(id, false);
        if (value == null) throw new RunFrozen();
        return value;
    }
    private Run requiredLocked(String id) {
        Run value = store.run(id, true);
        if (value == null) throw new RunFrozen();
        return value;
    }
    private void requireLease(Run run, String nodeId, String token) {
        if (!nodeId.equals(run.claimNodeId()) || run.claimHash() == null
                || !MessageDigest.isEqual(run.claimHash(), RunLeaseTokenCodec.digest(token))
                || run.leaseUntil() == null || !run.leaseUntil().isAfter(clock.instant())) throw new RunFrozen();
    }
    private static Outcome outcome(Run run) {
        return new Outcome(run.id(), run.deliveryState(), run.accepted(), run.excluded(),
                run.pending(), run.revision());
    }
    private static String controlHash(String operation, String businessJson) {
        ObjectNode value = JSON.createObjectNode();
        value.put("operation", operation);
        value.set("business", CanonicalJson.parse(businessJson));
        return CanonicalJson.hash(Domain.CONTROL_BUSINESS, value);
    }
    private static byte[] attemptHash(String node, String operation, String business,
                                      String token, Long revision) {
        ObjectNode value = JSON.createObjectNode();
        value.put("node_id", node);
        value.put("operation", operation);
        value.put("business_hash", business);
        ObjectNode attempt = value.putObject("attempt");
        attempt.put("claim_token", token);
        if (revision == null) attempt.putNull("expected_run_revision");
        else attempt.put("expected_run_revision", revision);
        return hex(CanonicalJson.hash(Domain.CONTROL_ATTEMPT, value));
    }
    private static String partJson(Part part) {
        ObjectNode value = JSON.createObjectNode();
        value.put("run_id", part.runId());
        value.put("part_no", part.partNo());
        value.put("part_hash", part.partHash());
        ArrayNode array = value.putArray("members");
        for (Member member : part.members()) array.add(CanonicalJson.parse(memberJson(member)));
        return new String(CanonicalJson.bytes(value), StandardCharsets.UTF_8);
    }
    private static String memberJson(Member member) {
        ObjectNode value = JSON.createObjectNode();
        value.put("member_id", member.memberId());
        nullable(value, "source_item_id", member.sourceItemId());
        value.put("observation_id", member.observationId());
        value.put("payload_hash", member.payloadHash());
        nullable(value, "replaces_observation_id", member.replacesObservationId());
        return new String(CanonicalJson.bytes(value), StandardCharsets.UTF_8);
    }
    private static String resultJson(Result result) {
        ObjectNode value = JSON.createObjectNode();
        value.put("run_id", result.runId());
        value.put("manifest_hash", result.manifestHash());
        value.put("part_count", result.partCount());
        value.put("expected_count", result.expectedCount());
        value.put("fetch_outcome", result.fetchOutcome());
        value.set("coverage", CanonicalJson.parse(result.coverageJson()));
        return new String(CanonicalJson.bytes(value), StandardCharsets.UTF_8);
    }
    private static void nullable(ObjectNode value, String key, String text) {
        if (text == null) value.putNull(key); else value.put(key, text);
    }
    private static boolean identity(String text) { return text != null && text.matches("[A-Za-z0-9._:-]{1,64}"); }
    private static boolean hashText(String text) { return text != null && text.matches("[0-9a-f]{64}"); }
    private static byte[] hex(String text) { return HexFormat.of().parseHex(text); }
}
