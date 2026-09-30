package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.ObservationEnvelope;
import com.lianxibu.site.repository.articles.JdbcObservationStore;
import com.lianxibu.site.repository.articles.JdbcObservationStore.Receipt;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Receives one immutable wire observation and commits its ACK with its evidence. */
public final class ObservationIngressService {
    public record RunSnapshot(String id, String sourceId, LocalDate businessDate,
                              long configVersion, String configSnapshotJson,
                              String activationStage, String deliveryState,
                              Instant windowStart, Instant windowEnd) {
        public RunSnapshot(String id, String sourceId, LocalDate businessDate, long configVersion,
                           String configSnapshotJson, String activationStage, String deliveryState) {
            this(id, sourceId, businessDate, configVersion, configSnapshotJson, activationStage,
                    deliveryState, businessDate.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant(),
                    businessDate.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
        }
    }
    public interface RunReader {
        RunSnapshot locked(String runId);
    }
    public interface NodeAuthorizer {
        Principal authorize(String bearer, String sourceId);
    }
    public interface ArticleWriter {
        ArticleOutcome accept(ArticleCompatibilityWriter.Input input, LocalDate businessDate);
    }
    @FunctionalInterface public interface RunFinalizer { void recompute(String runId); }
    public record ArticleOutcome(String articleId, String evidenceRevisionId,
                                 String projectionDecision, long articleRevision) { }
    public record Ack(String observationId, String memberId, String status, String code,
                      String receiptRef, Instant acceptedAt, String projectionDecision,
                      Long currentArticleRevision) { }
    public static final class IdentityConflict extends RuntimeException {
        public IdentityConflict() { super("observation identity conflict"); }
    }
    public static final class CapacityGuarded extends RuntimeException {
        public CapacityGuarded() { super("capacity_guard"); }
    }
    private static final Set<String> CLOSED = Set.of("complete", "partial", "no_usable_input", "empty_success");
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter();
    private final JdbcObservationStore store;
    private final RunReader runs;
    private final SharedWriteExecutor writes;
    private final NodeAuthorizer nodes;
    private final ArticleWriter articles;
    private final Clock clock;
    private final RunFinalizer finalizer;
    private final java.util.function.BooleanSupplier uploadAllowed;

    public ObservationIngressService(JdbcObservationStore store, RunReader runs,
                                     SharedWriteExecutor writes, NodeAuthorizer nodes,
                                     ArticleWriter articles, Clock clock) {
        this(store, runs, writes, nodes, articles, ignored -> { }, clock, () -> false);
    }
    public ObservationIngressService(JdbcObservationStore store, RunReader runs,
                                     SharedWriteExecutor writes, NodeAuthorizer nodes,
                                     ArticleWriter articles, Clock clock,
                                     java.util.function.BooleanSupplier uploadAllowed) {
        this(store,runs,writes,nodes,articles,ignored -> { },clock,uploadAllowed);
    }

    public ObservationIngressService(JdbcObservationStore store, RunReader runs,
                                     SharedWriteExecutor writes, NodeAuthorizer nodes,
                                     ArticleWriter articles, RunFinalizer finalizer, Clock clock) {
        this(store,runs,writes,nodes,articles,finalizer,clock,() -> false);
    }
    public ObservationIngressService(JdbcObservationStore store, RunReader runs,
                                     SharedWriteExecutor writes, NodeAuthorizer nodes,
                                     ArticleWriter articles, RunFinalizer finalizer, Clock clock,
                                     java.util.function.BooleanSupplier uploadAllowed) {
        if (store == null || runs == null || writes == null || nodes == null
                || articles == null || finalizer == null || clock == null || uploadAllowed == null) {
            throw new IllegalArgumentException("observation dependencies required");
        }
        this.store = store;
        this.runs = runs;
        this.writes = writes;
        this.nodes = nodes;
        this.articles = articles;
        this.clock = clock;
        this.finalizer = finalizer;
        this.uploadAllowed = uploadAllowed;
    }

    public ObservationIngressService(JdbcObservationStore store, RunReader runs,
                                     SharedWriteExecutor writes, NodeIdentityService nodes,
                                     ArticleCompatibilityWriter articles,
                                     ArticleProjectionService projection, Clock clock) {
        this(store, runs, writes, nodes, articles, projection, ignored -> { }, clock);
    }

    public ObservationIngressService(JdbcObservationStore store, RunReader runs,
                                     SharedWriteExecutor writes, NodeIdentityService nodes,
                                     ArticleCompatibilityWriter articles,
                                     ArticleProjectionService projection, RunFinalizer finalizer,
                                     Clock clock) {
        this(store, runs, writes,
                (bearer, sourceId) -> nodes.authorizeBearer(bearer, "observation:write", sourceId),
                (input, day) -> {
                    var compatibility = articles.accept(input);
                    var decided = projection.decide(input, compatibility, day);
                    return new ArticleOutcome(decided.articleId(),
                            decided.evidenceRevisionId(), decided.projectionDecision(),
                            decided.articleRevision());
                }, finalizer, clock);
    }

    /** Caller supplies the exact outbox bytes; no normalization or network work occurs under lock. */
    public Ack accept(String bearer, byte[] rawJson, ActionContext action) {
        if (rawJson == null || rawJson.length > 512 * 1024 || action == null) {
            throw new IllegalArgumentException("observation outside wire limit");
        }
        ObservationEnvelope observation = ObservationEnvelope.fromJson(CanonicalJson.parse(rawJson));
        if (!observation.observationId().equals(action.actionKey())) throw new IdentityConflict();
        Prepared candidate;
        try {
            candidate = prepare(observation);
        } catch (IllegalArgumentException invalidPayload) {
            candidate = null;
        }
        final Prepared prepared = candidate;
        Principal initial = nodes.authorize(bearer, observation.sourceId());
        return writes.execute(action, key -> {
            Receipt receipt = store.receipt(initial.nodeId(), observation.sourceId(),
                    observation.observationId());
            if (receipt == null) return null;
            nodes.authorize(bearer, observation.sourceId());
            return replay(receipt, observation, initial.nodeId());
        }, context -> {
            Principal currentNode = nodes.authorize(bearer, observation.sourceId());
            Receipt existing = store.receipt(currentNode.nodeId(), observation.sourceId(),
                    observation.observationId());
            if (existing != null) return replay(existing, observation, currentNode.nodeId());
            if (!uploadAllowed.getAsBoolean()) throw new CapacityGuarded();
            if (prepared == null) {
                return persist(context, currentNode.nodeId(), observation, null,
                        "rejected", "invalid_payload", null, null, null, null,
                        null, null, false);
            }
            RunSnapshot run = runs.locked(observation.runId());
            if (run == null || !run.sourceId().equals(observation.sourceId())
                    || !run.businessDate().equals(prepared.businessDate())
                    || run.configVersion() != prepared.sourceRevision()
                    || !validFrozen(run, prepared)) {
                return persist(context, currentNode.nodeId(), observation, null,
                        "blocked", "historical_config_unverifiable", null, null, null, null,
                        null, prepared, false);
            }
            if (CLOSED.contains(run.deliveryState())) {
                return persist(context, currentNode.nodeId(), observation, run.id(),
                        "rejected", "run_closed_unknown_observation", null, null, null, null,
                        null, prepared, false);
            }
            var prior = store.member(run.id(), observation.memberId());
            JsonNode replaces = observation.wire().get("replaces_observation_id");
            boolean reserved = prior != null && "pending".equals(prior.terminalState())
                    && prior.observationId().equals(observation.observationId());
            if (prior == null && !replaces.isNull()) throw new IdentityConflict();
            if (prior != null && !reserved) {
                if (replaces.isNull() || !prior.observationId().equals(replaces.asText())
                        || !"accepted".equals(prior.terminalState())
                        || store.manifestStarted(run.id()) || !store.supersede(prior)) {
                    throw new IdentityConflict();
                }
            }
            if (!reserved && store.itemIdentityTaken(run.id(), observation.sourceId(),
                    observation.sourceItemId())) throw new IdentityConflict();
            String articleId = null;
            String revisionId = null;
            String decision = null;
            Long articleRevision = null;
            Prepared qualified = prepared;
            if ("article".equals(observation.kind())) {
                var input = articleInput(observation, prepared);
                qualified = prepared.withExclusion(ArticleWindowQualification.exclusion(input,
                        run.windowStart(), run.windowEnd()));
                var written = articles.accept(input,
                        prepared.businessDate());
                articleId = written.articleId();
                revisionId = written.evidenceRevisionId();
                decision = written.projectionDecision();
                articleRevision = written.articleRevision();
            }
            Ack accepted = persist(context, currentNode.nodeId(), observation, run.id(),
                    "accepted", "accepted", articleId, revisionId, decision,
                    prepared.evidenceJson(), articleRevision, qualified, true);
            finalizer.recompute(run.id());
            return accepted;
        });
    }

    private Ack persist(ActionContext action, String nodeId, ObservationEnvelope observation,
                        String registeredRunId, String status, String code, String articleId,
                        String revisionId, String decision, String evidenceJson,
                        Long articleRevision, Prepared prepared, boolean member) {
        Instant now = clock.instant();
        String receiptId = action.preallocatedResourceId();
        Ack ack = new Ack(observation.observationId(), observation.memberId(), status, code,
                receiptId, "accepted".equals(status) ? now : null, decision, articleRevision);
        store.saveReceipt(receiptId, nodeId, observation, registeredRunId, status,
                json(ack, observation.runId()), now);
        if (member) {
            store.insertMember(UUID.randomUUID().toString(), nodeId, observation,
                    prepared.category(), receiptId, "accepted", evidenceJson,
                    articleId, revisionId, decision, now, prepared.exclusionReason());
        }
        return ack;
    }

    private static Ack replay(Receipt receipt, ObservationEnvelope observation, String nodeId) {
        if (!receipt.nodeId().equals(nodeId)
                || !receipt.sourceId().equals(observation.sourceId())
                || !receipt.observationId().equals(observation.observationId())
                || !receipt.memberId().equals(observation.memberId())
                || receipt.payloadHash() == null
                || !MessageDigest.isEqual(receipt.payloadHash(),
                    HexFormat.of().parseHex(observation.payloadHash()))) {
            throw new IdentityConflict();
        }
        JsonNode value = CanonicalJson.parse(receipt.ackJson());
        String expectedRun = value.get("run_id").asText();
        if (!expectedRun.equals(observation.runId())) throw new IdentityConflict();
        String status = value.get("status").asText();
        return new Ack(observation.observationId(), observation.memberId(),
                "accepted".equals(status) ? "duplicate" : status,
                value.get("code").asText(), receipt.id(),
                value.get("accepted_at").isNull() ? null
                        : Instant.parse(value.get("accepted_at").asText()),
                value.get("projection_decision").isNull() ? null
                        : value.get("projection_decision").asText(),
                value.get("current_article_revision").isNull() ? null
                        : value.get("current_article_revision").longValue());
    }

    private static boolean validFrozen(RunSnapshot run, Prepared prepared) {
        try {
            JsonNode config = CanonicalJson.parse(run.configSnapshotJson());
            Instant until = Instant.parse(config.get("valid_until").asText());
            return prepared.retrievedAt().isBefore(until)
                    && prepared.parserVersion().equals(config.get("parser_version").asText())
                    && prepared.payloadConfigVersion() == run.configVersion()
                    && !run.activationStage().isBlank();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static Prepared prepare(ObservationEnvelope value) {
        if (value.sourceItemId().getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new IllegalArgumentException("source item identity exceeds storage limit");
        }
        JsonNode wire = value.wire();
        JsonNode payload = wire.get("payload");
        ObservationPayloadPolicy.validate(value);
        LocalDate day = LocalDate.parse(wire.get("business_date").asText());
        long sourceRevision = wire.get("source_revision").longValue();
        Instant retrieved = Instant.parse(wire.get("retrieved_at").asText());
        if (!payload.get("config_version").isIntegralNumber()
                || payload.get("config_version").longValue() != sourceRevision
                || !payload.get("parser_version").isTextual()
                || payload.get("parser_version").asText().isBlank()) {
            throw new IllegalArgumentException("invalid observation parser/config binding");
        }
        String category = switch (value.kind()) {
            case "article" -> "news_tech";
            case "catalog" -> payload.get("category").asText();
            case "metric" -> "model";
            default -> throw new IllegalArgumentException("unsupported observation kind");
        };
        if (!Set.of("news_tech", "agent", "skill", "workflow", "model").contains(category)) {
            throw new IllegalArgumentException("invalid evidence category");
        }
        String evidence = new String(CanonicalJson.bytes(payload), StandardCharsets.UTF_8);
        return new Prepared(day, sourceRevision, retrieved, category, evidence,
                payload.get("parser_version").asText(), payload.get("config_version").longValue(), null);
    }

    private static ArticleCompatibilityWriter.Input articleInput(ObservationEnvelope value,
                                                                   Prepared prepared) {
        JsonNode payload = value.wire().get("payload");
        if (payload.get("config_version").longValue() != prepared.sourceRevision()) {
            throw new IllegalArgumentException("article config revision mismatch");
        }
        String timeBasis = payload.get("time_basis").asText();
        return new ArticleCompatibilityWriter.Input(value.sourceId(), value.sourceItemId(),
                payload.get("title").asText(), payload.get("source_url").asText(),
                nullableTime(payload.get("published_at")), nullableText(payload.get("source_summary")),
                nullableText(payload.get("body_text")), timeBasis,
                payload.get("content_status").asText(), payload.get("body_truncated").booleanValue(),
                payload.get("parser_version").asText(), prepared.sourceRevision(),
                new String(CanonicalJson.bytes(payload.get("source_order")), StandardCharsets.UTF_8),
                nullableTime(payload.get("updated_at")), nullableTime(payload.get("submitted_at")));
    }

    private static String json(Ack ack, String runId) {
        ObjectNode node = JsonMapper.shared().createObjectNode();
        node.put("observation_id", ack.observationId());
        node.put("member_id", ack.memberId());
        node.put("run_id", runId);
        node.put("status", ack.status());
        node.put("code", ack.code());
        node.put("receipt_ref", ack.receiptRef());
        if (ack.acceptedAt() == null) node.putNull("accepted_at");
        else node.put("accepted_at", MICROS.format(ack.acceptedAt()));
        if (ack.projectionDecision() == null) node.putNull("projection_decision");
        else node.put("projection_decision", ack.projectionDecision());
        if (ack.currentArticleRevision() == null) node.putNull("current_article_revision");
        else node.put("current_article_revision", ack.currentArticleRevision());
        return new String(CanonicalJson.bytes(node), StandardCharsets.UTF_8);
    }
    private static String nullableText(JsonNode node) { return node.isNull() ? null : node.asText(); }
    private static Instant nullableTime(JsonNode node) {
        return node == null || node.isNull() ? null : Instant.parse(node.asText());
    }
    private record Prepared(LocalDate businessDate, long sourceRevision, Instant retrievedAt,
                            String category, String evidenceJson, String parserVersion,
                            long payloadConfigVersion, String exclusionReason) {
        Prepared withExclusion(String reason) {
            return new Prepared(businessDate, sourceRevision, retrievedAt, category, evidenceJson,
                    parserVersion, payloadConfigVersion, reason);
        }
    }
}
