package com.lianxibu.site.service.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.learning.LearningImportService.Part;
import com.lianxibu.site.service.learning.LearningImportService.Rejected;
import com.lianxibu.site.service.learning.LearningImportService.Staged;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Private bounded file staging; all names are server-generated digests or part numbers. */
public final class FileLearningImportStages implements LearningImportService.StageStore {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Duration TTL = Duration.ofHours(24);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();
    private final Path root;

    public FileLearningImportStages(Path configuredRoot) {
        if (configuredRoot == null) throw new IllegalArgumentException("staging root required");
        try {
            Files.createDirectories(configuredRoot);
            root = configuredRoot.toRealPath();
            if (root.toString().length() > 350)
                throw new IllegalArgumentException("staging root path too long");
            Files.createDirectories(root.resolve("actors"));
            Files.createDirectories(root.resolve("owners"));
        } catch (IOException error) {
            throw new IllegalStateException("staging root unavailable", error);
        }
    }

    @Override public Staged save(String actor, String target, String importId, Part part, Instant now) {
        return locked(() -> {
            Path actorDir = actorDir(actor);
            Path metaPath = actorDir.resolve("meta.json");
            Path ownerPath = ownerPath(importId);
            reclaimExpiredOwner(ownerPath, importId, now);
            verifyOwner(ownerPath, actor);
            if (Files.exists(metaPath)) {
                Meta meta = readMeta(metaPath);
                if (!now.isBefore(meta.expiresAt())) {
                    discard(actorDir, ownerPath);
                } else if (!meta.importId().equals(importId) || !equal(meta.target(), target)
                        || meta.partCount() != part.partCount()) {
                    throw new Rejected("active_import_conflict");
                }
            }
            if (!Files.exists(metaPath)) {
                Files.createDirectories(actorDir);
                Meta meta = new Meta(importId, target, part.partCount(), now.plus(TTL));
                if (!Files.exists(ownerPath))
                    atomic(ownerPath, actor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                atomic(metaPath, metaBytes(meta));
            }
            Meta meta = readMeta(metaPath);
            Path path = partPath(actorDir, part.partNo());
            byte[] payload = partBytes(part);
            if (payload.length > 256 * 1024) throw new Rejected("import_part_too_large");
            if (Files.exists(path)) {
                if (!java.util.Arrays.equals(Files.readAllBytes(path), payload))
                    throw new Rejected("part_hash_conflict");
            } else {
                long total = 0;
                try (var files = Files.list(actorDir)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().startsWith("part-")).toList()) {
                        total += Files.size(file);
                    }
                }
                if (total + payload.length > MAX_BYTES) throw new Rejected("import_total_too_large");
                atomic(path, payload);
            }
            return new Staged(meta.importId(), actor, meta.target(), meta.expiresAt(), List.of());
        });
    }

    @Override public Staged load(String actor, String target, String importId, Instant now) {
        return locked(() -> {
            Path owner = ownerPath(importId);
            verifyOwner(owner, actor);
            Path actorDir = actorDir(actor);
            Path metaPath = actorDir.resolve("meta.json");
            if (!Files.exists(metaPath)) throw new Rejected("import_missing");
            Meta meta = readMeta(metaPath);
            if (!meta.importId().equals(importId) || !equal(meta.target(), target))
                throw new Rejected("import_identity_conflict");
            if (!now.isBefore(meta.expiresAt())) {
                discard(actorDir, owner);
                throw new Rejected("import_expired");
            }
            return snapshot(actorDir, actor, meta);
        });
    }

    @Override public void clear(String actor, String target, String importId) {
        locked(() -> {
            Path actorDir = actorDir(actor);
            Path metaPath = actorDir.resolve("meta.json");
            if (!Files.exists(metaPath)) return null;
            Meta meta = readMeta(metaPath);
            if (!meta.importId().equals(importId) || !equal(meta.target(), target))
                throw new Rejected("import_identity_conflict");
            discard(actorDir, ownerPath(importId));
            return null;
        });
    }

    private Staged snapshot(Path actorDir, String actor, Meta meta) throws IOException {
        List<Part> parts = new ArrayList<>();
        try (var files = Files.list(actorDir)) {
            for (Path path : files.filter(p -> p.getFileName().toString().matches("part-[0-9]+\\.json")).toList()) {
                JsonNode value = CanonicalJson.parse(Files.readAllBytes(path));
                JsonNode items = value.get("items");
                if (items == null || !items.isArray()) throw new Rejected("staged_part_corrupt");
                List<JsonNode> collected = new ArrayList<>();
                items.forEach(collected::add);
                parts.add(new Part(value.get("part_no").asLong(), value.get("part_count").asLong(),
                        value.get("part_hash").asText(), List.copyOf(collected)));
            }
        }
        return new Staged(meta.importId(), actor, meta.target(), meta.expiresAt(), List.copyOf(parts));
    }

    private static byte[] partBytes(Part part) {
        ObjectNode value = JSON.createObjectNode();
        value.put("part_no", part.partNo());
        value.put("part_count", part.partCount());
        value.put("part_hash", part.partHash());
        ArrayNode items = JSON.createArrayNode();
        part.items().forEach(items::add);
        value.set("items", items);
        return CanonicalJson.bytes(value);
    }

    private static byte[] metaBytes(Meta meta) {
        ObjectNode value = JSON.createObjectNode();
        value.put("import_id", meta.importId());
        if (meta.target() == null) value.putNull("target_package_id");
        else value.put("target_package_id", meta.target());
        value.put("part_count", meta.partCount());
        value.put("expires_at_ms", meta.expiresAt().toEpochMilli());
        return CanonicalJson.bytes(value);
    }

    private static Meta readMeta(Path path) throws IOException {
        JsonNode value = CanonicalJson.parse(Files.readAllBytes(path));
        JsonNode target = value.get("target_package_id");
        return new Meta(value.get("import_id").asText(), target.isNull() ? null : target.asText(),
                value.get("part_count").asLong(), Instant.ofEpochMilli(value.get("expires_at_ms").asLong()));
    }

    private void verifyOwner(Path owner, String actor) throws IOException {
        if (Files.exists(owner) && !actor.equals(Files.readString(owner)))
            throw new Rejected("cross_user_import");
    }
    private void reclaimExpiredOwner(Path owner, String importId, Instant now) throws IOException {
        if (!Files.exists(owner)) return;
        Path previousActorDir = actorDir(Files.readString(owner));
        Path metadata = previousActorDir.resolve("meta.json");
        if (!Files.exists(metadata)) {
            Files.deleteIfExists(owner);
            return;
        }
        Meta old = readMeta(metadata);
        if (!old.importId().equals(importId)) Files.deleteIfExists(owner);
        else if (!now.isBefore(old.expiresAt()))
            discard(previousActorDir, owner);
    }
    private void discard(Path actorDir, Path owner) throws IOException {
        if (Files.exists(actorDir)) {
            try (var files = Files.list(actorDir)) {
                for (Path path : files.toList()) Files.delete(path);
            }
            Files.delete(actorDir);
        }
        Files.deleteIfExists(owner);
    }
    private Path actorDir(String actor) { return safe(root.resolve("actors").resolve(digest(actor))); }
    private Path ownerPath(String importId) { return safe(root.resolve("owners").resolve(digest(importId))); }
    private Path partPath(Path dir, long no) { return safe(dir.resolve("part-" + no + ".json")); }
    private Path safe(Path path) {
        Path normalized = path.normalize();
        if (!normalized.startsWith(root) || normalized.toString().length() > 512)
            throw new IllegalArgumentException("unsafe staging path");
        return normalized;
    }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private static void atomic(Path target, byte[] value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
    private static boolean equal(String left, String right) {
        return java.util.Objects.equals(left, right);
    }
    private interface IO<T> { T run() throws IOException; }
    private <T> T locked(IO<T> work) {
        ReentrantLock local = LOCAL_LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        local.lock();
        try {
            try (FileChannel channel = FileChannel.open(root.resolve(".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return work.run();
            } catch (IOException error) {
                throw new IllegalStateException("import staging unavailable", error);
            }
        } finally {
            local.unlock();
        }
    }
    private record Meta(String importId, String target, long partCount, Instant expiresAt) { }
}
