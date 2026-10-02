package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.content.JdbcContentReads.Row;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Review binds the exact body, frozen input owner and complete validity vector. */
public final class ReviewPublicationService implements ContentReadService.ReferenceProof {
    public record Dependency(JsonNode targetKey, long validityRevision, boolean effectiveValid) {
        public Dependency { targetKey=copy(targetKey); }
        @Override public JsonNode targetKey(){return copy(targetKey);}
    }
    public record Frozen(String inputJobId, byte[] inputHash, List<JsonNode> completeTargets,
                         boolean referencesComplete) {
        public Frozen { inputHash=inputHash==null?null:inputHash.clone();completeTargets=completeTargets==null?null:completeTargets.stream().map(ReviewPublicationService::copy).toList(); }
        @Override public byte[] inputHash(){return inputHash==null?null:inputHash.clone();}
        @Override public List<JsonNode> completeTargets(){return completeTargets==null?null:completeTargets.stream().map(ReviewPublicationService::copy).toList();}
    }
    public record Current(ReviewContentState row, Frozen frozen, List<Dependency> dependencies,
                          boolean ownVersionValid) {
        public Current { dependencies=dependencies==null?null:List.copyOf(dependencies); }
    }
    public record Receipt(String kind, String id, long revision, String state,
                          String stampHash) { }
    public interface Store {
        default void verifyCommand(String operation,String kind,String id,long expectedRevision,String bodyHash) { }
        Receipt committed(String actionKey);
        /** Caller holds the shared write entry lock; lock all stable target identities in fixed order. */
        Current lock(String kind, String id);
        /** Read frozen targets and validity in the same consistent snapshot as the content row. */
        Current snapshot(ReviewContentState row);
        void approve(ReviewContentState expected, Stamp stamp, String actionKey);
        void publish(ReviewContentState expected, String actionKey);
        void edit(ReviewContentState expected, String bodyJson, byte[] bodyHash, String actionKey);
    }
    public record Stamp(String json, String hash) { }
    public static final class Conflict extends RuntimeException {
        public Conflict(String reason) { super(reason); }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode copy(JsonNode node){return node==null?null:CanonicalJson.parse(CanonicalJson.bytes(node));}
    private final SharedWriteExecutor writes;
    private final Store store;

    public ReviewPublicationService(SharedWriteExecutor writes, Store store) {
        if (writes == null || store == null) throw new IllegalArgumentException("review dependencies required");
        this.writes = writes;
        this.store = store;
    }

    public Receipt approve(String kind, String id, long expectedRevision, ActionContext action) {
        return writes.execute(action, key -> committed(key,"approve",kind,id,expectedRevision,null), ignored -> {
            Current current = requireCurrent(kind, id, expectedRevision);
            Stamp stamp = stamp(current);
            store.approve(current.row(), stamp, action.actionKey());
            return new Receipt(kind, id, expectedRevision + 1, "approved", stamp.hash());
        });
    }

    public Receipt publish(String kind, String id, long expectedRevision, ActionContext action) {
        return writes.execute(action, key -> committed(key,"publish",kind,id,expectedRevision,null), ignored -> {
            Current current = requireCurrent(kind, id, expectedRevision);
            ReviewContentState row = current.row();
            if (!"approved".equals(row.state()) || !matches(row, current)) {
                throw new Conflict("review_required");
            }
            store.publish(row, action.actionKey());
            return new Receipt(kind, id, expectedRevision + 1, "published",
                    HexFormat.of().formatHex(row.reviewStampHash()));
        });
    }

    public Receipt edit(String kind, String id, long expectedRevision, String bodyJson,
                        ActionContext action) {
        JsonNode body = CanonicalJson.parse(bodyJson);
        if (!body.isObject()) throw new IllegalArgumentException("content body must be an object");
        byte[] hash = HexFormat.of().parseHex(CanonicalJson.hash(Domain.CONTENT, body));
        return writes.execute(action, key -> committed(key,"edit",kind,id,expectedRevision,HexFormat.of().formatHex(hash)), ignored -> {
            Current current = requireCurrent(kind, id, expectedRevision);
            store.edit(current.row(), bodyJson, hash, action.actionKey());
            return new Receipt(kind, id, expectedRevision + 1, "draft", null);
        });
    }

    private Receipt committed(String key,String operation,String kind,String id,long expected,String bodyHash){store.verifyCommand(operation,kind,id,expected,bodyHash);return store.committed(key);}

    @Override public boolean valid(Row row) {
        try {
            return row != null && "published".equals(row.state())
                    && validSnapshot(ReviewContentState.fromRow(row));
        } catch (IllegalArgumentException | IllegalStateException | Conflict invalid) {
            return false;
        }
    }

    private boolean validSnapshot(ReviewContentState row){return matches(row,store.snapshot(row));}

    private Current requireCurrent(String kind, String id, long expectedRevision) {
        if (kind == null || id == null || id.isBlank() || expectedRevision < 1) {
            throw new IllegalArgumentException("invalid content identity");
        }
        Current current = store.lock(kind, id);
        if (current == null || current.row() == null) throw new Conflict("content_missing");
        if (current.row().revision() != expectedRevision) throw new Conflict("revision_conflict");
        return current;
    }

    private static boolean matches(ReviewContentState row, Current current) {
        if (current == null || current.row() == null || current.row() != row
                || row.bodyRevision() == null || row.bodyHashV1() == null
                || row.reviewHashV1() == null || row.reviewStampHash() == null
                || row.reviewStampId() == null || row.reviewDependencyStamp() == null
                || row.reviewedInputHash() == null) return false;
        try {
            Stamp stamp = stamp(current);
            return Arrays.equals(row.bodyHashV1(), row.reviewHashV1())
                    && Arrays.equals(row.reviewStampHash(), HexFormat.of().parseHex(stamp.hash()))
                    && Arrays.equals(row.reviewedInputHash(), current.frozen().inputHash())
                    && Arrays.equals(CanonicalJson.bytes(CanonicalJson.parse(row.reviewDependencyStamp())),
                            CanonicalJson.bytes(CanonicalJson.parse(stamp.json())));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static Stamp stamp(Current current) {
        if (current == null || current.row() == null || current.frozen() == null
                || !current.ownVersionValid()) throw new Conflict("invalid_evidence");
        ReviewContentState row = current.row();
        Frozen frozen = current.frozen();
        if (row.bodyJson() == null || row.bodyHashV1() == null || row.inputJobId() == null
                || frozen.inputJobId() == null || frozen.inputHash() == null
                || frozen.inputHash().length != 32 || !frozen.referencesComplete()
                || !row.inputJobId().equals(frozen.inputJobId())) throw new Conflict("invalid_evidence");
        String bodyHash = CanonicalJson.hash(Domain.CONTENT, CanonicalJson.parse(row.bodyJson()));
        if (!Arrays.equals(row.bodyHashV1(), HexFormat.of().parseHex(bodyHash))) {
            throw new Conflict("body_hash_mismatch");
        }
        List<JsonNode> targets = frozen.completeTargets();
        List<Dependency> dependencies = current.dependencies();
        if (targets == null || dependencies == null || targets.size() != dependencies.size()) {
            throw new Conflict("incomplete_references");
        }
        Set<String> frozenKeys = new HashSet<>();
        for (JsonNode target : targets) frozenKeys.add(targetKey(target));
        if (frozenKeys.size() != targets.size()) throw new Conflict("duplicate_reference");
        List<Dependency> sorted = new ArrayList<>(dependencies);
        sorted.sort((a,b) -> Arrays.compareUnsigned(CanonicalJson.bytes(a.targetKey()),
                CanonicalJson.bytes(b.targetKey())));
        ArrayNode vector = JSON.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (Dependency dependency : sorted) {
            String key = targetKey(dependency.targetKey());
            if (!seen.add(key) || !frozenKeys.contains(key) || !dependency.effectiveValid()
                    || dependency.validityRevision() < 0
                    || dependency.validityRevision() > CanonicalJson.MAX_SAFE_INTEGER) {
                throw new Conflict("invalid_evidence");
            }
            ObjectNode entry = JSON.createObjectNode();
            entry.set("target_key", dependency.targetKey());
            entry.put("validity_revision", dependency.validityRevision());
            vector.add(entry);
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("body_hash", bodyHash);
        payload.put("input_job_id", row.inputJobId());
        payload.put("input_hash", HexFormat.of().formatHex(frozen.inputHash()));
        payload.set("dependencies", vector);
        String json = new String(CanonicalJson.bytes(vector), StandardCharsets.UTF_8);
        return new Stamp(json, CanonicalJson.hash(Domain.REVIEW, payload));
    }

    private static String targetKey(JsonNode target) {
        if (target == null || !target.isObject() || target.get("kind") == null
                || !target.get("kind").isTextual()) throw new Conflict("invalid_target_key");
        return new String(CanonicalJson.bytes(target), StandardCharsets.UTF_8);
    }
}
