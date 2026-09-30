package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.content.JdbcContentWrites;
import com.lianxibu.site.repository.content.JdbcContentWrites.Row;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Preserves old content keys while accepting only a reviewed complete business identity. */
public final class ContentCompatibilityWriter {
    public interface SnapshotProof {
        /** Must verify that this job owns the complete key and a frozen input in the same transaction. */
        boolean valid(String inputJobId, String canonicalContentKeyJson);
    }
    public interface ConversionJournal {
        /** Controlled migration journal adapter; failure must abort the database transaction. */
        void record(ConversionReceipt receipt);
    }
    public record ConversionReceipt(String kind, String id, String naturalKey, long bodyRevision,
                                    String oldRowSha256, String contentKeyHash) { }
    public record ReviewedLegacyContent(String kind, String id, String naturalKey,
                                        String contentKeyJson, String rowSha256) {
        public ReviewedLegacyContent {
            if (!KINDS.contains(kind) || blank(id) || blank(naturalKey) || blank(contentKeyJson)
                    || rowSha256 == null || !rowSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid reviewed content mapping");
            }
            validateKey(kind, CanonicalJson.parse(contentKeyJson));
        }
        @Override public String toString() { return "ReviewedLegacyContent[kind=" + kind + ",id=" + id + "]"; }
    }
    public record Input(String kind, String contentKeyJson, String title, String bodyJson,
                        String inputJobId) { }
    public record Result(String kind, String id, String naturalKey, long bodyRevision,
                         boolean inserted, boolean legacyConverted) { }

    public static final class ContentMappingRequired extends RuntimeException {
        public ContentMappingRequired() { super("content identity mapping is incomplete"); }
    }
    public static final class IdentityConflict extends RuntimeException {
        public IdentityConflict() { super("content identity conflict"); }
    }

    private static final Set<String> KINDS = Set.of("report", "agent", "skill", "workflow", "model");
    private final JdbcContentWrites store;
    private final Map<String, Map<String, ReviewedLegacyContent>> reviewed;
    private final TransactionTemplate writes;
    private final Clock clock;
    private final SnapshotProof snapshots;
    private final ConversionJournal journal;

    public ContentCompatibilityWriter(JdbcContentWrites store, PlatformTransactionManager tx,
                                      Clock clock, SnapshotProof snapshots, ConversionJournal journal,
                                      List<ReviewedLegacyContent> mappings) {
        if (store == null || tx == null || clock == null || snapshots == null
                || journal == null || mappings == null) {
            throw new IllegalArgumentException("content writer dependencies required");
        }
        this.store = store;
        this.clock = clock;
        this.snapshots = snapshots;
        this.journal = journal;
        Map<String, Map<String, ReviewedLegacyContent>> draft = new HashMap<>();
        Map<String, Set<String>> keys = new HashMap<>();
        for (ReviewedLegacyContent mapping : mappings) {
            if (mapping == null || draft.computeIfAbsent(mapping.kind(), ignored -> new HashMap<>())
                    .putIfAbsent(mapping.id(), mapping) != null
                    || !keys.computeIfAbsent(mapping.kind(), ignored -> new HashSet<>())
                    .add(canonical(CanonicalJson.parse(mapping.contentKeyJson())))) {
                throw new IllegalArgumentException("conflicting reviewed content mapping");
            }
        }
        Map<String, Map<String, ReviewedLegacyContent>> frozen = new HashMap<>();
        draft.forEach((key, value) -> frozen.put(key, Map.copyOf(value)));
        reviewed = Map.copyOf(frozen);
        writes = new TransactionTemplate(tx);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result accept(Input input) {
        if (input == null || !KINDS.contains(input.kind()) || blank(input.title())
                || input.title().length() > 512 || blank(input.inputJobId())
                || input.inputJobId().length() > 64 || blank(input.contentKeyJson())
                || input.contentKeyJson().getBytes(StandardCharsets.UTF_8).length > 4096
                || blank(input.bodyJson())
                || input.bodyJson().getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw new IllegalArgumentException("invalid content input");
        }
        JsonNode key = CanonicalJson.parse(input.contentKeyJson());
        validateKey(input.kind(), key);
        JsonNode body = CanonicalJson.parse(input.bodyJson());
        if (!body.isObject() || body.get("title") == null || !body.get("title").isTextual()
                || !input.title().equals(body.get("title").asText())) {
            throw new IllegalArgumentException("content title mismatch");
        }
        String keyJson = canonical(key);
        String bodyJson = canonical(body);
        String keyHash = CanonicalJson.hash(Domain.CONTENT_KEY, key);
        byte[] keyHashBytes = HexFormat.of().parseHex(keyHash);
        byte[] bodyHash = HexFormat.of().parseHex(CanonicalJson.hash(Domain.CONTENT, body));
        String naturalKey = "v1:" + keyHash;
        String type = key.get("identity").get("type").asText();
        byte[] periodHash = "period".equals(type) ? keyHashBytes : null;
        String entityId = "entity".equals(type) ? key.get("identity").get("entity_id").asText() : null;
        return writes.execute(status -> {
            store.lockSharedEntry();
            if (!snapshots.valid(input.inputJobId(), keyJson)) {
                throw new ContentMappingRequired();
            }
            Map<String, ReviewedLegacyContent> mappings = coverage(input.kind());
            Row matched = null;
            for (Row row : store.kindRows(input.kind())) {
                String rowKey = row.contentKeyJson();
                if (rowKey == null) {
                    ReviewedLegacyContent approved = mappings.get(row.id());
                    rowKey = approved == null ? null : approved.contentKeyJson();
                }
                if (rowKey != null && keyJson.equals(canonical(CanonicalJson.parse(rowKey)))) {
                    if (matched != null) throw new IdentityConflict();
                    matched = row;
                }
                if (naturalKey.equals(row.naturalKey()) && rowKey != null
                        && !keyJson.equals(canonical(CanonicalJson.parse(rowKey)))) {
                    throw new IdentityConflict();
                }
            }
            if (matched != null) return reuse(matched, keyJson, keyHash, periodHash, entityId);
            try {
                String id = UUID.randomUUID().toString();
                store.insert(new JdbcContentWrites.NewContent(input.kind(), id, naturalKey,
                        input.title(), bodyJson, keyJson, periodHash, entityId, bodyHash,
                        input.inputJobId(), clock.instant()));
                return new Result(input.kind(), id, naturalKey, 1, true, false);
            } catch (DataIntegrityViolationException conflict) {
                throw new IdentityConflict();
            }
        });
    }

    private Result reuse(Row row, String keyJson, String keyHash, byte[] periodHash, String entityId) {
        if (row.contentKeyJson() != null) {
            if (!keyJson.equals(canonical(CanonicalJson.parse(row.contentKeyJson())))) {
                throw new IdentityConflict();
            }
            return new Result(row.kind(), row.id(), row.naturalKey(),
                    row.bodyRevision() == null ? row.revision() : row.bodyRevision(), false, false);
        }
        if (!"draft".equals(row.state())) throw new ContentMappingRequired();
        JsonNode oldBody = CanonicalJson.parse(row.bodyJson());
        byte[] oldBodyHash = HexFormat.of().parseHex(CanonicalJson.hash(Domain.CONTENT, oldBody));
        String oldRowHash = snapshotSha256(row);
        store.convert(row, keyJson, periodHash, entityId, oldBodyHash);
        journal.record(new ConversionReceipt(row.kind(), row.id(), row.naturalKey(),
                row.revision(), oldRowHash, keyHash));
        return new Result(row.kind(), row.id(), row.naturalKey(), row.revision(), false, true);
    }

    private Map<String, ReviewedLegacyContent> coverage(String kind) {
        Map<String, ReviewedLegacyContent> mappings = reviewed.getOrDefault(kind, Map.of());
        Set<String> seen = new HashSet<>();
        for (Row row : store.kindRows(kind)) {
            ReviewedLegacyContent mapping = mappings.get(row.id());
            if (row.contentKeyJson() == null && mapping == null) throw new ContentMappingRequired();
            if (mapping == null) continue;
            if (!mapping.naturalKey().equals(row.naturalKey())
                    || !mapping.rowSha256().equals(snapshotSha256(row))
                    || row.contentKeyJson() != null && !canonical(CanonicalJson.parse(mapping.contentKeyJson()))
                    .equals(canonical(CanonicalJson.parse(row.contentKeyJson())))) {
                throw new ContentMappingRequired();
            }
            seen.add(row.id());
        }
        if (seen.size() != mappings.size()) throw new ContentMappingRequired();
        return mappings;
    }

    public static String snapshotSha256(Row row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, row.kind());
            field(digest, row.id());
            field(digest, row.naturalKey());
            field(digest, row.title());
            field(digest, row.bodyJson());
            digest.update(ByteBuffer.allocate(8).putLong(row.revision()).array());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void validateKey(String kind, JsonNode key) {
        if (!key.isObject() || key.size() != 2 || key.get("content_kind") == null
                || !kind.equals(key.get("content_kind").asText())
                || key.get("identity") == null || !key.get("identity").isObject()) {
            throw new IllegalArgumentException("invalid content identity");
        }
        JsonNode identity = key.get("identity");
        String type = text(identity, "type");
        if ("period".equals(type)) {
            if (!"report".equals(kind) || identity.size() != 4
                    || !"Asia/Shanghai".equals(text(identity, "timezone"))) {
                throw new IllegalArgumentException("invalid period identity");
            }
            String startRaw = text(identity, "period_start");
            String endRaw = text(identity, "period_end");
            if (!startRaw.endsWith("Z") || !endRaw.endsWith("Z")) {
                throw new IllegalArgumentException("period bounds must be UTC");
            }
            Instant start = Instant.parse(startRaw);
            Instant end = Instant.parse(endRaw);
            if (!start.isBefore(end) || start.getNano() % 1000 != 0 || end.getNano() % 1000 != 0) {
                throw new IllegalArgumentException("invalid period bounds");
            }
        } else if ("entity".equals(type)) {
            if ("report".equals(kind) || identity.size() != 3
                    || !kind.equals(text(identity, "category"))
                    || blank(text(identity, "entity_id")) || text(identity, "entity_id").length() > 64) {
                throw new IllegalArgumentException("invalid entity identity");
            }
        } else if ("manual".equals(type)) {
            if (identity.size() != 2 || blank(text(identity, "intent_id"))
                    || text(identity, "intent_id").length() > 64) {
                throw new IllegalArgumentException("invalid manual identity");
            }
        } else {
            throw new IllegalArgumentException("invalid identity type");
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("invalid identity field");
        return value.asText();
    }
    private static String canonical(JsonNode value) {
        return new String(CanonicalJson.bytes(value), StandardCharsets.UTF_8);
    }
    private static void field(MessageDigest digest, String value) {
        if (value == null) { digest.update((byte) 0); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
