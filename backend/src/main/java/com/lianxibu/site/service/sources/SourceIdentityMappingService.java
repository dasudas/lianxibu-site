package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.sources.JdbcSourceReads;
import com.lianxibu.site.repository.sources.JdbcSourceReads.Row;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Checks a privately reviewed, per-source identity against the current legacy row. */
public final class SourceIdentityMappingService {
    /** The catalog is supplied by controlled host configuration; no production mappings live in Git. */
    public record ReviewedMapping(String sourceId, String sourceKind, String configSha256,
                                  String entryUrl, String category) {
        public ReviewedMapping {
            if (sourceId == null || sourceId.isBlank() || sourceId.length() > 80
                    || sourceKind == null || sourceKind.isBlank()
                    || configSha256 == null || !configSha256.matches("[0-9a-f]{64}")
                    || entryUrl == null || entryUrl.isBlank()
                    || category == null || category.isBlank()) {
                throw new IllegalArgumentException("invalid reviewed source mapping");
            }
            validEntry(entryUrl);
        }

        @Override public String toString() {
            return "ReviewedMapping[sourceId=" + sourceId + "]";
        }
    }

    /** Only the original ID is exposed to downstream writers; legacy JSON remains private. */
    public record WriterIdentity(String sourceId, String category) { }

    public static final class UnmappedSourceException extends RuntimeException {
        public UnmappedSourceException() { super("source identity is not reviewed or has changed"); }
    }

    private final JdbcSourceReads store;
    private final Map<String, ReviewedMapping> reviewed;
    private final TransactionTemplate reads;

    public SourceIdentityMappingService(JdbcSourceReads store, PlatformTransactionManager tx,
                                        List<ReviewedMapping> reviewedMappings) {
        if (store == null || tx == null || reviewedMappings == null) {
            throw new IllegalArgumentException("source mapping dependencies required");
        }
        this.store = store;
        Map<String, ReviewedMapping> catalog = new HashMap<>();
        Map<String, String> endpointOwners = new HashMap<>();
        for (ReviewedMapping mapping : reviewedMappings) {
            if (mapping == null || catalog.putIfAbsent(mapping.sourceId(), mapping) != null) {
                throw new IllegalArgumentException("duplicate or null reviewed source mapping");
            }
            String endpointKey = mapping.sourceKind() + "\u0000" + mapping.entryUrl();
            if (endpointOwners.putIfAbsent(endpointKey, mapping.sourceId()) != null) {
                throw new IllegalArgumentException("conflicting reviewed source endpoint");
            }
        }
        reviewed = Map.copyOf(catalog);
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Rechecks the current row on every call; a stale approval never grants a writer identity. */
    public WriterIdentity writerIdentity(String sourceId) {
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 80) {
            throw new UnmappedSourceException();
        }
        ReviewedMapping approval = reviewed.get(sourceId);
        if (approval == null) throw new UnmappedSourceException();
        return reads.execute(status -> verify(approval, store.byId(sourceId)));
    }

    private static WriterIdentity verify(ReviewedMapping approval, Row row) {
        if (row == null || !approval.sourceId().equals(row.id())
                || !approval.sourceKind().equals(row.sourceKind())
                || (row.category() != null && !approval.category().equals(row.category()))
                || row.legacyConfigJson() == null
                || !approval.configSha256().equals(sha256(row.legacyConfigJson()))) {
            throw new UnmappedSourceException();
        }
        try {
            JsonNode config = CanonicalJson.parse(row.legacyConfigJson());
            if (!config.isObject() || !matchesEntry(config, approval.entryUrl())) {
                throw new UnmappedSourceException();
            }
        } catch (IllegalArgumentException invalidConfig) {
            throw new UnmappedSourceException();
        }
        return new WriterIdentity(row.id(), approval.category());
    }

    private static boolean matchesEntry(JsonNode config, String expected) {
        JsonNode direct = config.get("url");
        if (direct == null) direct = config.get("entry_url");
        if (direct != null && direct.isTextual()) return expected.equals(direct.asText());
        JsonNode entries = config.get("entry_urls");
        if (entries == null || !entries.isArray() || entries.size() != 1) return false;
        JsonNode entry = entries.get(0);
        return entry.isTextual() && expected.equals(entry.asText());
    }

    private static void validEntry(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid reviewed source entry");
            }
        } catch (IllegalArgumentException badUri) {
            throw new IllegalArgumentException("invalid reviewed source entry");
        }
    }

    public static String sha256(String value) {
        if (value == null) throw new IllegalArgumentException("source config required");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
