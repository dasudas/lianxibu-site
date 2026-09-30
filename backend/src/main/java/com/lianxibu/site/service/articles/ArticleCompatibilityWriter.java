package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.articles.JdbcArticleWrites;
import com.lianxibu.site.repository.articles.JdbcArticleWrites.Row;
import com.lianxibu.site.service.sources.SourceIdentityMappingService;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Compatibility seam for first article insert and reviewed legacy baseline conversion. */
public final class ArticleCompatibilityWriter {
    public record ReviewedLegacyArticle(String articleId, String sourceId, String externalId,
                                        String sourceItemId, String rowSha256) {
        public ReviewedLegacyArticle {
            if (blank(articleId) || blank(sourceId) || blank(externalId) || blank(sourceItemId)
                    || rowSha256 == null || !rowSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid reviewed article mapping");
            }
        }
        @Override public String toString() { return "ReviewedLegacyArticle[articleId=" + articleId + "]"; }
    }

    public record Input(String sourceId, String sourceItemId, String title, String sourceUrl,
                        Instant publishedAt, String summary, String body, String timeBasis,
                        String contentStatus, boolean bodyTruncated, String parserVersion,
                        long configVersion, String sourceOrderJson, Instant updatedAt,
                        Instant submittedAt) {
        public Input(String sourceId, String sourceItemId, String title, String sourceUrl,
                     Instant publishedAt, String summary, String body, String timeBasis,
                     String contentStatus, boolean bodyTruncated, String parserVersion,
                     long configVersion, String sourceOrderJson) {
            this(sourceId,sourceItemId,title,sourceUrl,"published".equals(timeBasis)?publishedAt:null,summary,body,timeBasis,
                    contentStatus,bodyTruncated,parserVersion,configVersion,sourceOrderJson,
                    "updated".equals(timeBasis)?publishedAt:null,null);
        }
        public Instant sourceTime() {
            return switch(timeBasis) {case "published" -> publishedAt;case "updated" -> updatedAt;
                case "submitted" -> submittedAt;default -> null;};
        }
    }

    public record Result(String articleId, String revisionId, boolean inserted,
                         boolean legacyBaselineCreated) { }

    public static final class SourceMappingRequired extends RuntimeException {
        public SourceMappingRequired() { super("source article mapping is incomplete"); }
    }
    public static final class IdentityConflict extends RuntimeException {
        public IdentityConflict() { super("article identity conflict"); }
    }

    private final JdbcArticleWrites store;
    private final SourceIdentityMappingService sources;
    private final Map<String, Map<String, ReviewedLegacyArticle>> reviewed;
    private final TransactionTemplate writes;
    private final Clock clock;

    public ArticleCompatibilityWriter(JdbcArticleWrites store, SourceIdentityMappingService sources,
                                      PlatformTransactionManager tx, Clock clock,
                                      List<ReviewedLegacyArticle> mappings) {
        if (store == null || sources == null || tx == null || clock == null || mappings == null) {
            throw new IllegalArgumentException("article writer dependencies required");
        }
        this.store = store;
        this.sources = sources;
        this.clock = clock;
        Map<String, Map<String, ReviewedLegacyArticle>> draft = new HashMap<>();
        Map<String, Set<String>> itemOwners = new HashMap<>();
        for (ReviewedLegacyArticle mapping : mappings) {
            if (mapping == null || draft.computeIfAbsent(mapping.sourceId(), ignored -> new HashMap<>())
                    .putIfAbsent(mapping.articleId(), mapping) != null
                    || !itemOwners.computeIfAbsent(mapping.sourceId(), ignored -> new HashSet<>())
                    .add(mapping.sourceItemId())) {
                throw new IllegalArgumentException("conflicting reviewed article mapping");
            }
        }
        Map<String, Map<String, ReviewedLegacyArticle>> frozen = new HashMap<>();
        draft.forEach((key, value) -> frozen.put(key, Map.copyOf(value)));
        reviewed = Map.copyOf(frozen);
        writes = new TransactionTemplate(tx);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** The caller supplies a previously validated normalized observation, never a guessed old ID. */
    public Result accept(Input input) {
        validate(input);
        return writes.execute(status -> {
            store.lockSharedEntry();
            sources.writerIdentity(input.sourceId());
            Map<String, ReviewedLegacyArticle> mappings = coverage(input.sourceId());
            byte[] itemHash = hash(Domain.SOURCE_ITEM, input.sourceId(), "source_item_id",
                    input.sourceItemId());
            Row byHash = store.byNewIdentity(input.sourceId(), itemHash);
            if (byHash != null) {
                if (!input.sourceItemId().equals(byHash.sourceItemId())) throw new IdentityConflict();
                return new Result(byHash.id(), byHash.latestRevisionId(), false, false);
            }
            ReviewedLegacyArticle mapped = mappings.values().stream()
                    .filter(value -> input.sourceItemId().equals(value.sourceItemId()))
                    .findFirst().orElse(null);
            if (mapped != null) {
                Row old = store.byExternal(input.sourceId(), mapped.externalId());
                if (old == null || !mapped.articleId().equals(old.id()) || old.sourceItemId() != null) {
                    throw new IdentityConflict();
                }
                String revisionId = UUID.randomUUID().toString();
                Instant now = clock.instant();
                store.insertRevision(new JdbcArticleWrites.Revision(revisionId, old.id(),
                        projectionHash(old.title(), old.summary(), old.body()), old.title(),
                        old.summary(), old.body(), null, "unknown", old.body() == null
                        ? "unavailable" : "partial", false, "legacy-baseline", 0,
                        null, true, now));
                store.attachNewRevision(old.id(), revisionId, itemHash, input.sourceItemId());
                return new Result(old.id(), revisionId, false, true);
            }
            if (store.byExternal(input.sourceId(), input.sourceItemId()) != null) {
                throw new IdentityConflict();
            }
            return insert(input, itemHash);
        });
    }

    private Map<String, ReviewedLegacyArticle> coverage(String sourceId) {
        Map<String, ReviewedLegacyArticle> mappings = reviewed.getOrDefault(sourceId, Map.of());
        Set<String> seen = new HashSet<>();
        for (Row row : store.sourceRows(sourceId)) {
            ReviewedLegacyArticle mapping = mappings.get(row.id());
            if (row.sourceItemId() == null && mapping == null) throw new SourceMappingRequired();
            if (mapping == null) continue; // A new writer-created article is outside the old catalog.
            if (!mapping.sourceId().equals(row.sourceId())
                    || !mapping.externalId().equals(row.externalId())
                    || !mapping.rowSha256().equals(snapshotSha256(row))
                    || (row.sourceItemId() != null
                        && !mapping.sourceItemId().equals(row.sourceItemId()))) {
                throw new SourceMappingRequired();
            }
            seen.add(row.id());
        }
        if (seen.size() != mappings.size()) throw new SourceMappingRequired();
        return mappings;
    }

    private Result insert(Input input, byte[] itemHash) {
        String id = UUID.randomUUID().toString();
        String revisionId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        byte[] projectionHash = projectionHash(input.title(), input.summary(), input.body());
        ObjectNode facts = JsonMapper.shared().createObjectNode();
        facts.put("schema_version", "1");
        facts.put("parser_version", input.parserVersion());
        facts.put("config_version", input.configVersion());
        facts.put("time_basis", input.timeBasis());
        facts.put("content_status", input.contentStatus());
        facts.put("body_truncated", input.bodyTruncated());
        facts.put("published_at", input.publishedAt()==null?null:input.publishedAt().toString());
        facts.put("updated_at", input.updatedAt()==null?null:input.updatedAt().toString());
        facts.put("submitted_at", input.submittedAt()==null?null:input.submittedAt().toString());
        if (input.sourceOrderJson() == null) facts.putNull("source_order");
        else facts.set("source_order", CanonicalJson.parse(input.sourceOrderJson()));
        byte[] externalKey = hash(Domain.LEGACY_EXTERNAL, input.sourceId(), "external_id",
                input.sourceItemId());
        try {
            store.insert(new JdbcArticleWrites.NewArticle(id, input.sourceId(), input.sourceItemId(),
                    itemHash, externalKey, input.title(), input.sourceUrl(), input.publishedAt(),
                    input.summary(), input.body(), new String(CanonicalJson.bytes(facts),
                    StandardCharsets.UTF_8), projectionHash, now));
            store.insertRevision(new JdbcArticleWrites.Revision(revisionId, id, projectionHash,
                    input.title(), input.summary(), input.body(), input.sourceTime(),
                    input.timeBasis(), input.contentStatus(), input.bodyTruncated(),
                    input.parserVersion(), input.configVersion(), input.sourceOrderJson(), false, now,
                    input.publishedAt(),input.updatedAt(),input.submittedAt()));
            store.attachInsertedRevision(id, revisionId);
        } catch (DataIntegrityViolationException conflict) {
            Row byHash = store.byNewIdentity(input.sourceId(), itemHash);
            Row byExternal = store.byExternal(input.sourceId(), input.sourceItemId());
            if (byHash != null && !id.equals(byHash.id()) && byHash.latestRevisionId() != null
                    && input.sourceItemId().equals(byHash.sourceItemId())
                    && (byExternal == null || byHash.id().equals(byExternal.id()))) {
                return new Result(byHash.id(), byHash.latestRevisionId(), false, false);
            }
            throw new IdentityConflict();
        }
        return new Result(id, revisionId, true, false);
    }

    private static void validate(Input input) {
        if (input == null || blank(input.sourceId()) || input.sourceId().length() > 80
                || blank(input.sourceItemId()) || bytes(input.sourceItemId()) > 4096
                || blank(input.title()) || input.title().length() > 512
                || blank(input.sourceUrl()) || input.sourceUrl().length() > 2000
                || input.summary() != null && input.summary().length() > 16384
                || input.body() != null && (input.body().length() > 50000 || bytes(input.body()) > 204800)
                || !Set.of("published", "updated", "submitted", "unknown").contains(input.timeBasis())
                || !Set.of("full", "partial", "unavailable").contains(input.contentStatus())
                || blank(input.parserVersion()) || input.parserVersion().length() > 64
                || input.configVersion() < 0 || input.configVersion() > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("invalid normalized article");
        }
        if ("submitted".equals(input.timeBasis()) && (input.submittedAt()==null
                || input.publishedAt()!=null || input.updatedAt()!=null)
                || !"submitted".equals(input.timeBasis()) && input.submittedAt()!=null) {
            throw new IllegalArgumentException("invalid article time combination");
        }
        if (input.sourceOrderJson() != null && !CanonicalJson.parse(input.sourceOrderJson()).isObject()) {
            throw new IllegalArgumentException("invalid source order");
        }
    }

    public static String snapshotSha256(Row row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, row.id());
            field(digest, row.sourceId());
            field(digest, row.externalId());
            binary(digest, row.externalKey());
            field(digest, row.title());
            field(digest, row.url());
            field(digest, row.publishedAt() == null ? null : row.publishedAt().toString());
            field(digest, row.summary());
            field(digest, row.body());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] projectionHash(String title, String summary, String body) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("title", title);
        if (summary == null) value.putNull("summary"); else value.put("summary", summary);
        if (body == null) value.putNull("body_text"); else value.put("body_text", body);
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.ARTICLE_PROJECTION, value));
    }

    private static byte[] hash(Domain domain, String sourceId, String identityName, String identity) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("source_id", sourceId);
        value.put(identityName, identity);
        return HexFormat.of().parseHex(CanonicalJson.hash(domain, value));
    }

    private static void field(MessageDigest digest, String value) {
        if (value == null) { digest.update((byte) 0); return; }
        digest.update((byte) 1);
        binary(digest, value.getBytes(StandardCharsets.UTF_8));
    }
    private static void binary(MessageDigest digest, byte[] value) {
        if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
        digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
        digest.update(value);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
