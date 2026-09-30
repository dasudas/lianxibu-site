package com.lianxibu.site.service.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Staged learning import; existing packages are updated only by explicitly supplied items. */
public final class LearningImportService {
    public record Part(long partNo, long partCount, String partHash, List<JsonNode> items) { }
    public record Staged(String importId, String actorId, String targetPackageId,
                         Instant expiresAt, List<Part> parts) { }
    public record Progress(String importId, long acceptedPartNo, String partHash,
                           Instant expiresAt) { }
    public record Plan(String importId, String targetPackageId, String temporaryPackageId,
                       String title, String description, List<JsonNode> items,
                       String manifestHash) { }
    public record Result(String packageId, long revision, boolean replayed) { }
    public interface StageStore {
        /** Save by server generated actor/import/target identity, using an atomic file rename. */
        Staged save(String actorId, String targetPackageId, String importId,
                    Part part, Instant now);
        Staged load(String actorId, String targetPackageId, String importId, Instant now);
        void clear(String actorId, String targetPackageId, String importId);
    }
    public interface CommitStore {
        Result committed(String actionKey, String commandHash);
        /** Under the shared entry lock, validate existing refs, package CAS and stable item locks;
         * write only explicit items as draft and the durable receipt in this transaction. */
        Result apply(Plan plan, String actorId, Long expectedRevision,
                     String commandHash, ActionContext action);
    }
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final StageStore staging;
    private final CommitStore commits;
    private final SharedWriteExecutor writes;
    private final Clock clock;

    public LearningImportService(StageStore staging, CommitStore commits,
                                 SharedWriteExecutor writes, Clock clock) {
        if (staging == null || commits == null || writes == null || clock == null)
            throw new IllegalArgumentException("import dependencies required");
        this.staging = staging;
        this.commits = commits;
        this.writes = writes;
        this.clock = clock;
    }

    public Progress stage(String actorId, String targetPackageId, LearningService.ImportChunk chunk) {
        actor(actorId);
        target(targetPackageId);
        if (chunk == null || !id(chunk.importId()) || chunk.partCount() < 1
                || chunk.partCount() > 100_000 || chunk.partNo() < 0
                || chunk.partNo() >= chunk.partCount() || chunk.items() == null
                || chunk.items().isEmpty() || chunk.items().size() > 50) {
            throw new IllegalArgumentException("invalid import chunk");
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("import_id", chunk.importId());
        payload.put("part_no", chunk.partNo());
        payload.put("part_count", chunk.partCount());
        ArrayNode items = JSON.createArrayNode();
        for (JsonNode item : chunk.items()) {
            if (item == null || !item.isObject()
                    || CanonicalJson.bytes(item).length > 256 * 1024)
                throw new Rejected("import_item_too_large");
            items.add(item);
        }
        payload.set("items", items);
        byte[] bytes = CanonicalJson.bytes(payload);
        if (bytes.length > 256 * 1024) throw new Rejected("import_part_too_large");
        String computed = digest("learning-import-part-v1", bytes);
        if (!computed.equals(chunk.partHash())) throw new Rejected("part_hash_mismatch");
        Staged stored = staging.save(actorId, targetPackageId, chunk.importId(),
                new Part(chunk.partNo(), chunk.partCount(), computed, List.copyOf(chunk.items())),
                clock.instant());
        return new Progress(chunk.importId(), chunk.partNo(), computed, stored.expiresAt());
    }

    public Result commit(String actorId, String targetPackageId, Long expectedRevision,
                         LearningService.ImportFinalize command, ActionContext action) {
        actor(actorId);
        target(targetPackageId);
        if (command == null || action == null || !id(command.importId())
                || command.partCount() < 1 || command.partCount() > 100_000
                || command.title() == null || command.title().isBlank()
                || command.description() == null || command.manifestHash() == null
                || expectedRevision != null && expectedRevision < 1
                || targetPackageId == null && expectedRevision != null
                || targetPackageId != null && expectedRevision == null) {
            throw new IllegalArgumentException("invalid import commit");
        }
        String commandHash = commandHash(actorId, targetPackageId, expectedRevision, command);
        Result existing = commits.committed(action.actionKey(), commandHash);
        if (existing != null) {
            cleanup(actorId, targetPackageId, command.importId());
            return new Result(existing.packageId(), existing.revision(), true);
        }
        Staged staged = staging.load(actorId, targetPackageId, command.importId(), clock.instant());
        Plan plan = validate(staged, command);
        Result result = writes.execute(action, key -> commits.committed(key, commandHash), ignored ->
                commits.apply(plan, actorId, expectedRevision, commandHash, action));
        cleanup(actorId, targetPackageId, command.importId());
        return result;
    }

    private void cleanup(String actorId, String target, String importId) {
        try { staging.clear(actorId, target, importId); }
        catch (RuntimeException ignored) { /* committed receipt permits later cleanup */ }
    }

    public static Plan validate(Staged staged, LearningService.ImportFinalize command) {
        if (staged == null || !command.importId().equals(staged.importId())
                || staged.parts() == null || staged.parts().size() != command.partCount())
            throw new Rejected("missing_import_part");
        List<Part> sorted = new ArrayList<>(staged.parts());
        sorted.sort(java.util.Comparator.comparingLong(Part::partNo));
        ArrayNode parts = JSON.createArrayNode();
        List<JsonNode> all = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        String temporaryPackageId = null;
        for (int index = 0; index < sorted.size(); index++) {
            Part part = sorted.get(index);
            if (part.partNo() != index || part.partCount() != command.partCount()
                    || part.items() == null || part.items().isEmpty())
                throw new Rejected("missing_import_part");
            ObjectNode canonicalPart = JSON.createObjectNode();
            canonicalPart.put("import_id", command.importId());
            canonicalPart.put("part_no", part.partNo());
            canonicalPart.put("part_count", part.partCount());
            ArrayNode itemArray = JSON.createArrayNode();
            part.items().forEach(itemArray::add);
            canonicalPart.set("items", itemArray);
            if (!digest("learning-import-part-v1", CanonicalJson.bytes(canonicalPart))
                    .equals(part.partHash())) throw new Rejected("part_hash_mismatch");
            ObjectNode member = JSON.createObjectNode();
            member.put("part_no", part.partNo());
            member.put("part_hash", part.partHash());
            parts.add(member);
            for (JsonNode item : part.items()) {
                if (item == null || !item.isObject()) throw new Rejected("invalid_import_item");
                if (item.has("package_id")) {
                    JsonNode packageNode = item.get("package_id");
                    if (packageNode == null || !packageNode.isTextual() || !id(packageNode.asText()))
                        throw new Rejected("invalid_package_identity");
                    if (temporaryPackageId == null) temporaryPackageId = packageNode.asText();
                    else if (!temporaryPackageId.equals(packageNode.asText()))
                        throw new Rejected("mixed_package_identity");
                }
                if (item.has("id")) {
                    String kind = item.has("question") ? "question" : item.has("module_ids") ? "route"
                            : item.has("route_ids") ? "module" : item.has("tasks") ? "project" : "node";
                    String itemId = item.get("id").asText();
                    if (!id(itemId) || !identities.add(kind + ":" + itemId))
                        throw new Rejected("duplicate_import_identity");
                }
                all.add(item);
            }
        }
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("import_id", command.importId());
        manifest.put("part_count", command.partCount());
        manifest.set("parts", parts);
        String computed = digest("learning-import-v1", CanonicalJson.bytes(manifest));
        if (!computed.equals(command.manifestHash())) throw new Rejected("manifest_hash_mismatch");
        if (temporaryPackageId == null) throw new Rejected("missing_package_identity");
        return new Plan(command.importId(), staged.targetPackageId(), temporaryPackageId,
                command.title(), command.description(), List.copyOf(all), computed);
    }

    public static String partHash(String importId, long no, long count, List<JsonNode> items) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("import_id", importId);
        payload.put("part_no", no);
        payload.put("part_count", count);
        ArrayNode array = JSON.createArrayNode();
        items.forEach(array::add);
        payload.set("items", array);
        return digest("learning-import-part-v1", CanonicalJson.bytes(payload));
    }

    public static String manifestHash(String importId, List<Part> parts) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("import_id", importId);
        payload.put("part_count", parts.size());
        ArrayNode array = JSON.createArrayNode();
        parts.stream().sorted(java.util.Comparator.comparingLong(Part::partNo)).forEach(part -> {
            ObjectNode member = JSON.createObjectNode();
            member.put("part_no", part.partNo());
            member.put("part_hash", part.partHash());
            array.add(member);
        });
        payload.set("parts", array);
        return digest("learning-import-v1", CanonicalJson.bytes(payload));
    }

    private static String commandHash(String actor, String target, Long revision,
                                      LearningService.ImportFinalize command) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("actor_id", actor);
        if (target == null) payload.putNull("target_package_id");
        else payload.put("target_package_id", target);
        if (revision == null) payload.putNull("expected_revision");
        else payload.put("expected_revision", revision);
        payload.put("import_id", command.importId());
        payload.put("title", command.title());
        payload.put("description", command.description());
        payload.put("part_count", command.partCount());
        payload.put("manifest_hash", command.manifestHash());
        return digest("learning-import-command-v1", CanonicalJson.bytes(payload));
    }
    private static String digest(String domain, byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((domain + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(data);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private static void actor(String actorId) {
        if (!id(actorId)) throw new IllegalArgumentException("invalid actor");
    }
    private static void target(String target) {
        if (target != null && !id(target)) throw new IllegalArgumentException("invalid package");
    }
    private static boolean id(String value) {
        return value != null && !value.isBlank() && value.length() <= 64;
    }
}
