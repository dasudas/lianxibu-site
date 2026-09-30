package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.ContentVersionKey;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exact-version invalidation, with durable paged reverse-reference propagation. */
public final class VersionValidityService {
    public enum Action { INVALIDATE, REVERSE_INVALIDATION }
    public record Command(JsonNode target, long expectedValidityRevision, Action action,
                          String reason, String reversesDispositionId) { }
    public record Validity(long revision, boolean effectiveValid, Set<String> activeDispositionIds) { }
    public record Result(String dispositionId, String targetKey, long validityRevision,
                         boolean effectiveValid, boolean replayed) { }
    public record Dependent(JsonNode versionKey, long contentRevision, String inputJobId,
                            String reviewStampHash) { }
    public record Page(List<Dependent> items, String nextCursor) { }
    public record Work(String dispositionId, JsonNode target, String cursor) { }

    public interface Store {
        Result committed(String actionKey, String commandHash);
        /** Lock a stable article/item/content identity even before its first disposition exists. */
        void lockStableIdentity(JsonNode target);
        boolean exactVersionExists(JsonNode target);
        Validity validity(JsonNode target);
        String append(Command command, String commandHash, long nextRevision, String actionKey);
        /** Close public qualification of this exact current body in the disposition transaction. */
        void closeCurrentVersionIfExact(JsonNode target);
        /** Unique by disposition + exact target across queued AND completed work. */
        void enqueue(String dispositionId, JsonNode target);
        void withdrawDisplay(JsonNode currentVersion);
        Work nextWork();
        /** Scan frozen direct references in stable pages; no current-row inference. */
        Page directDependents(JsonNode target, String cursor, int limit);
        /** CAS management revision, input owner and review stamp; mismatches must be skipped. */
        boolean blockIfCurrent(Dependent dependent, String dispositionId);
        void advance(Work work, String nextCursor);
        void complete(Work work);
    }
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }

    private final SharedWriteExecutor writes;
    private final Store store;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public VersionValidityService(SharedWriteExecutor writes, Store store) {
        if (writes == null || store == null) throw new IllegalArgumentException("validity dependencies required");
        this.writes = writes;
        this.store = store;
    }

    public Result change(Command command, ActionContext action) {
        validate(command, action);
        String targetKey = canonicalTarget(command.target());
        String hash = commandHash(command, targetKey);
        Result prior = store.committed(action.actionKey(), hash);
        if (prior != null) return new Result(prior.dispositionId(), targetKey,
                prior.validityRevision(), prior.effectiveValid(), true);
        return writes.execute(action, key -> store.committed(key, hash), ignored -> {
            store.lockStableIdentity(command.target());
            if (!store.exactVersionExists(command.target())) throw new Rejected("unknown_version");
            Validity current = store.validity(command.target());
            if (current == null || current.activeDispositionIds() == null
                    || current.revision() != command.expectedValidityRevision()) {
                throw new Rejected("validity_revision_conflict");
            }
            if (command.action() == Action.REVERSE_INVALIDATION
                    && !current.activeDispositionIds().contains(command.reversesDispositionId())) {
                throw new Rejected("invalidation_not_active");
            }
            long next = current.revision() + 1;
            String dispositionId = store.append(command, hash, next, action.actionKey());
            if (dispositionId == null || dispositionId.isBlank()) throw new Rejected("disposition_missing");
            if (command.action() == Action.INVALIDATE) {
                store.closeCurrentVersionIfExact(command.target());
                store.enqueue(dispositionId, command.target());
            }
            boolean effective = command.action() == Action.REVERSE_INVALIDATION
                    && current.activeDispositionIds().size() == 1;
            return new Result(dispositionId, targetKey, next, effective, false);
        });
    }

    /** Display withdrawal changes only the current public qualification, not version validity. */
    public void displayAdjustment(JsonNode currentVersion, ActionContext action) {
        canonicalTarget(currentVersion);
        if (action == null) throw new IllegalArgumentException("withdraw action required");
        writes.execute(action, key -> null, ignored -> {
            store.lockStableIdentity(currentVersion);
            if (!store.exactVersionExists(currentVersion)) throw new Rejected("unknown_version");
            store.withdrawDisplay(currentVersion);
            return true;
        });
    }

    /** One bounded durable step; caller repeats while work exists. */
    public boolean propagateNext(ActionContext action) {
        if (action == null) throw new IllegalArgumentException("propagation action required");
        return writes.execute(action, key -> null, ignored -> {
            Work work = store.nextWork();
            if (work == null) return false;
            canonicalTarget(work.target());
            Page page = store.directDependents(work.target(), work.cursor(), 100);
            if (page == null || page.items() == null || page.items().size() > 100) {
                throw new Rejected("invalid_reference_page");
            }
            Set<String> seen = new HashSet<>();
            for (Dependent dependent : page.items()) {
                if (dependent == null || dependent.versionKey() == null
                        || !"content_version".equals(dependent.versionKey().path("kind").asText())) {
                    throw new Rejected("invalid_frozen_reference");
                }
                String child = canonicalTarget(dependent.versionKey());
                if (!seen.add(child) || dependent.contentRevision() < 1
                        || dependent.inputJobId() == null || dependent.inputJobId().isBlank()
                        || dependent.reviewStampHash() == null || dependent.reviewStampHash().isBlank()) {
                    throw new Rejected("invalid_frozen_reference");
                }
                store.blockIfCurrent(dependent, work.dispositionId());
                // Historical owner versions propagate even if the current body was replaced.
                store.enqueue(work.dispositionId(), dependent.versionKey());
            }
            if (page.nextCursor() == null) store.complete(work);
            else {
                if (page.items().isEmpty() || page.nextCursor().equals(work.cursor()))
                    throw new Rejected("stalled_reference_page");
                store.advance(work, page.nextCursor());
            }
            return true;
        });
    }

    private static void validate(Command command, ActionContext action) {
        if (command == null || action == null || command.action() == null
                || command.expectedValidityRevision() < 0
                || command.expectedValidityRevision() >= CanonicalJson.MAX_SAFE_INTEGER
                || command.reason() == null || command.reason().isBlank()
                || command.reason().length() > 2048) throw new IllegalArgumentException("invalid validity command");
        canonicalTarget(command.target());
        if (command.action() == Action.REVERSE_INVALIDATION
                && (command.reversesDispositionId() == null
                    || command.reversesDispositionId().isBlank())) {
            throw new IllegalArgumentException("reverse disposition required");
        }
        if (command.action() == Action.INVALIDATE && command.reversesDispositionId() != null) {
            throw new IllegalArgumentException("unexpected reversal target");
        }
    }

    private static String canonicalTarget(JsonNode target) {
        if (target == null || !target.isObject() || target.get("kind") == null
                || !target.get("kind").isTextual()) throw new IllegalArgumentException("invalid target key");
        String kind = target.get("kind").asText();
        if ("content_version".equals(kind)) {
            if (ContentVersionKey.fromJson(target).revision() < 1) {
                throw new IllegalArgumentException("invalid body revision");
            }
        } else if ("article_revision".equals(kind)) {
            if (target.size() != 2 || !textId(target.get("id")))
                throw new IllegalArgumentException("invalid article revision key");
        } else if ("catalog_evidence".equals(kind)) {
            if (target.size() != 2 || !textId(target.get("item_id")))
                throw new IllegalArgumentException("invalid catalog evidence key");
        } else {
            throw new IllegalArgumentException("invalid target kind");
        }
        return new String(CanonicalJson.bytes(target), StandardCharsets.UTF_8);
    }

    private static boolean textId(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank()
                && value.asText().length() <= 64;
    }

    private static String commandHash(Command command, String targetKey) {
        ObjectNode value = JSON.createObjectNode();
        value.set("target", CanonicalJson.parse(targetKey));
        value.put("expected_validity_revision", command.expectedValidityRevision());
        value.put("action", command.action().name());
        value.put("reason", command.reason());
        if (command.reversesDispositionId() == null) value.putNull("reverses_disposition_id");
        else value.put("reverses_disposition_id", command.reversesDispositionId());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("validity-command-v1\n".getBytes(StandardCharsets.UTF_8));
            digest.update(CanonicalJson.bytes(value));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
