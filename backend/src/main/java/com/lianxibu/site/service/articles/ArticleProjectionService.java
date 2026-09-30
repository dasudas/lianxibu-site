package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.articles.JdbcArticleProjection;
import com.lianxibu.site.repository.articles.JdbcArticleProjection.Current;
import com.lianxibu.site.repository.articles.JdbcArticleProjection.Revision;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter.Input;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter.Result;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Selects a current article revision only when a reviewed source order proves it newer. */
public final class ArticleProjectionService {
    public record TrustedOrderRule(String sourceId, String parserVersion,
                                   String basis, String domain) {
        public TrustedOrderRule {
            if (sourceId == null || sourceId.isBlank() || parserVersion == null
                    || parserVersion.isBlank() || !Set.of("sequence", "revision_time").contains(basis)
                    || domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("reviewed source order rule required");
            }
        }
    }
    public record Decision(String articleId, String evidenceRevisionId,
                           String currentRevisionId, long articleRevision,
                           String projectionDecision, String reason) { }
    public static final class ProjectionConflict extends RuntimeException {
        public ProjectionConflict() { super("article projection changed or order unverified"); }
    }
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcArticleProjection store;
    private final Set<TrustedOrderRule> approved;
    private final Clock clock;

    public ArticleProjectionService(JdbcArticleProjection store, List<TrustedOrderRule> rules,
                                    Clock clock) {
        if (store == null || rules == null || clock == null
                || rules.stream().anyMatch(value -> value == null)
                || Set.copyOf(rules).size() != rules.size()) {
            throw new IllegalArgumentException("projection dependencies required");
        }
        this.store = store;
        this.approved = Set.copyOf(rules);
        this.clock = clock;
    }

    /** Called in the same outer shared-entry transaction as observation/member/receipt writes. */
    public Decision decide(Input input, Result baseline, LocalDate businessDate) {
        requireTransaction();
        if (input == null || baseline == null || businessDate == null) throw new ProjectionConflict();
        Current current = store.current(baseline.articleId());
        if (current == null || !current.selectedRevisionId().equals(baseline.revisionId())) {
            throw new ProjectionConflict();
        }
        Revision selected = store.byId(current.selectedRevisionId());
        if (selected == null) throw new ProjectionConflict();
        Order incoming = verified(input.sourceId(), input.parserVersion(), input.sourceOrderJson());
        if (baseline.inserted()) {
            if (incoming != null && !store.advanceWatermark(current, input.sourceOrderJson())) {
                throw new ProjectionConflict();
            }
            return new Decision(current.id(), current.selectedRevisionId(),
                    current.selectedRevisionId(), current.revision() + (incoming == null ? 0 : 1),
                    "initialized", "first_evidence");
        }

        byte[] candidateHash = projectionHash(input.title(), input.summary(), input.body());
        boolean sameBody = Arrays.equals(candidateHash, current.selectedHash());
        Order waterline = verified(input.sourceId(), selected.parserVersion(), current.orderJson());
        Integer comparison = compare(incoming, waterline);
        if (sameBody) {
            boolean qualifiedTime = selected.sourceTime() == null && input.sourceTime() != null
                    && !"unknown".equals(input.timeBasis())
                    && (comparison == null && waterline == null && incoming != null
                        || comparison != null && comparison > 0);
            if (qualifiedTime) {
                return appendAndMaybePromote(current, input, candidateHash, "promoted",
                        "time_qualified", businessDate);
            }
            if (incoming != null && (waterline == null || comparison != null && comparison > 0)) {
                if (!store.advanceWatermark(current, input.sourceOrderJson())) throw new ProjectionConflict();
                return new Decision(current.id(), current.selectedRevisionId(),
                        current.selectedRevisionId(), current.revision() + 1,
                        "unchanged", "same_body_newer_order_watermark");
            }
            return new Decision(current.id(), current.selectedRevisionId(),
                    current.selectedRevisionId(), current.revision(), "unchanged", "same_body");
        }
        if (comparison != null && comparison > 0) {
            return appendAndMaybePromote(current, input, candidateHash, "promoted",
                    "verified_newer_order", businessDate);
        }
        Revision known = store.existingMaterial(current.id(), candidateHash,
                input.title(), input.summary(), input.body());
        if (known != null) {
            return new Decision(current.id(), known.id(), current.selectedRevisionId(),
                    current.revision(), comparison != null && comparison < 0
                    ? "historical" : "needs_review", "existing_material_evidence");
        }
        return appendAndMaybePromote(current, input, candidateHash,
                comparison != null && comparison < 0 ? "historical" : "needs_review",
                comparison != null && comparison < 0 ? "verified_older_order"
                        : "uncomparable_or_equal_order", businessDate);
    }

    /** Explicit owner caller supplies a reviewed evidence revision; CAS guards concurrent edits. */
    public Decision confirmPromote(String actorId, String actionKey, String articleId,
                                   String evidenceRevisionId, long expectedArticleRevision,
                                   String reason) {
        requireTransaction();
        if (actorId == null || actorId.isBlank() || actorId.length() > 64
                || actionKey == null || actionKey.isBlank() || actionKey.length() > 128
                || articleId == null || evidenceRevisionId == null || reason == null
                || reason.isBlank() || reason.length() > 500) throw new ProjectionConflict();
        var committed = store.manualAudit(actorId, actionKey);
        if (committed != null) {
            if (!articleId.equals(committed.articleId())
                    || !evidenceRevisionId.equals(committed.evidenceRevisionId())
                    || expectedArticleRevision != committed.expectedRevision()
                    || !reason.equals(committed.reason())) throw new ProjectionConflict();
            return new Decision(articleId, evidenceRevisionId, committed.selectedRevisionId(),
                    expectedArticleRevision + 1, "promoted", "manual_review");
        }
        Current current = store.current(articleId);
        Revision evidence = store.byId(evidenceRevisionId);
        if (current == null || evidence == null || !articleId.equals(evidence.articleId())
                || current.revision() != expectedArticleRevision) {
            throw new ProjectionConflict();
        }
        Instant now = clock.instant();
        Revision reviewed = new Revision(UUID.randomUUID().toString(), articleId,
                store.nextRevisionNo(articleId), evidence.contentHash(), evidence.title(),
                evidence.summary(), evidence.body(), evidence.sourceTime(), evidence.timeBasis(),
                evidence.contentStatus(), evidence.bodyTruncated(), evidence.parserVersion(),
                evidence.configVersion(), evidence.orderJson(), "[]", now,
                evidence.publishedAt(), evidence.updatedAt(), evidence.submittedAt());
        store.append(reviewed);
        if (!store.manualPromote(articleId, expectedArticleRevision,
                current.selectedRevisionId(), reviewed, facts(reviewed), now)) {
            throw new ProjectionConflict();
        }
        store.saveManualAudit(actorId, actionKey, articleId, evidenceRevisionId,
                reviewed.id(), expectedArticleRevision, reason, now);
        return new Decision(articleId, evidenceRevisionId, reviewed.id(),
                expectedArticleRevision + 1, "promoted", "manual_review");
    }

    private Decision appendAndMaybePromote(Current current, Input input, byte[] hash,
                                           String decision, String reason, LocalDate businessDate) {
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        String events = "promoted".equals(decision)
                ? events("time_qualified".equals(reason) ? "time_qualified" : "material_update",
                    reason, businessDate, now) : "[]";
        Revision revision = new Revision(id, current.id(), store.nextRevisionNo(current.id()),
                hash, input.title(), input.summary(), input.body(), input.sourceTime(),
                input.timeBasis(), input.contentStatus(), input.bodyTruncated(),
                input.parserVersion(), input.configVersion(), input.sourceOrderJson(), events, now,
                input.publishedAt(), input.updatedAt(), input.submittedAt());
        store.append(revision);
        if ("promoted".equals(decision)) {
            if (!store.promote(current, id, input.sourceOrderJson(), input.title(),
                    input.summary(), input.body(), input.publishedAt(), hash, facts(revision), now)) {
                throw new ProjectionConflict();
            }
        }
        return new Decision(current.id(), id,
                "promoted".equals(decision) ? id : current.selectedRevisionId(),
                current.revision() + ("promoted".equals(decision) ? 1 : 0), decision, reason);
    }

    private Order verified(String sourceId, String parserVersion, String raw) {
        if (raw == null) return null;
        try {
            JsonNode value = CanonicalJson.parse(raw);
            if (!value.isObject() || !value.get("basis").isTextual()
                    || !value.get("domain").isTextual() || !value.get("value").isTextual()) return null;
            String basis = value.get("basis").asText();
            String domain = value.get("domain").asText();
            if (!approved.contains(new TrustedOrderRule(sourceId, parserVersion, basis, domain))) {
                return null;
            }
            String literal = value.get("value").asText();
            if ("sequence".equals(basis)) {
                if (!literal.matches("0|[1-9][0-9]{0,38}")) return null;
                return new Order(basis, domain, new java.math.BigInteger(literal), null);
            }
            if (!literal.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z")) {
                return null;
            }
            return new Order(basis, domain, null, Instant.parse(literal));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Integer compare(Order candidate, Order waterline) {
        if (candidate == null || waterline == null
                || !candidate.basis().equals(waterline.basis())
                || !candidate.domain().equals(waterline.domain())) return null;
        return candidate.sequence() != null
                ? candidate.sequence().compareTo(waterline.sequence())
                : candidate.time().compareTo(waterline.time());
    }

    private static byte[] projectionHash(String title, String summary, String body) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("title", title);
        if (summary == null) value.putNull("summary"); else value.put("summary", summary);
        if (body == null) value.putNull("body_text"); else value.put("body_text", body);
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.ARTICLE_PROJECTION, value));
    }

    private static String facts(Revision revision) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("schema_version", "1");
        value.put("parser_version", revision.parserVersion());
        value.put("config_version", revision.configVersion());
        value.put("time_basis", revision.timeBasis());
        value.put("content_status", revision.contentStatus());
        value.put("body_truncated", revision.bodyTruncated());
        value.put("published_at", revision.publishedAt() == null ? null : MICROS.format(revision.publishedAt()));
        value.put("updated_at", revision.updatedAt() == null ? null : MICROS.format(revision.updatedAt()));
        value.put("submitted_at", revision.submittedAt() == null ? null : MICROS.format(revision.submittedAt()));
        if (revision.orderJson() == null) value.putNull("source_order");
        else value.set("source_order", CanonicalJson.parse(revision.orderJson()));
        return new String(CanonicalJson.bytes(value), StandardCharsets.UTF_8);
    }
    private static String events(String key, String reason, LocalDate day, Instant now) {
        ArrayNode array = JsonMapper.shared().createArrayNode();
        ObjectNode event = array.addObject();
        event.put("event_key", key);
        event.put("confirmed_at", MICROS.format(now));
        event.put("reason", reason);
        event.put("business_date", day.toString());
        return new String(CanonicalJson.bytes(array), StandardCharsets.UTF_8);
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("article projection requires shared write transaction");
        }
    }
    private record Order(String basis, String domain, java.math.BigInteger sequence, Instant time) { }
}
