package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission.Row;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.SourceAdmissionPolicy.ProbeEvidence;
import com.lianxibu.site.service.sources.SourceAdmissionPolicy.PromotionEvidence;
import com.lianxibu.site.service.sources.SourcesService.SourceConfig;
import com.lianxibu.site.service.sources.SourcesService.SourceCreate;
import com.lianxibu.site.service.sources.SourcesService.SourceWrite;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Controlled source draft, probe request and explicit activation transitions. */
public final class SourceAdmissionService {
    public interface EvidenceStore {
        /** Only persisted, version-bound, independently reviewed run evidence may be returned. */
        ProbeEvidence probe(String sourceId, long configVersion);
        PromotionEvidence promotion(String sourceId, long configVersion);
    }
    public interface ActionReceipts {
        /** Must use the same database transaction as the source mutation. */
        Decision committed(String actionKey);
        void record(String actionKey, Decision decision);
    }
    public record Decision(String sourceId, long revision, long configRevision,
                           String activationStage, String action) { }
    public record ProbeTicket(String sourceId, long configRevision, String configSha256,
                              String parserVersion) { }
    public static final class AdmissionDenied extends RuntimeException {
        public AdmissionDenied() { super("source admission evidence or revision insufficient"); }
    }

    private static final Set<String> CATEGORIES = Set.of("news_tech", "agent", "skill", "workflow", "model");
    private final JdbcSourceAdmission store;
    private final SourceIdentityMappingService identities;
    private final SharedWriteExecutor writes;
    private final SourceAdmissionPolicy policy;
    private final EvidenceStore evidence;
    private final ActionReceipts receipts;
    private final Clock clock;

    public SourceAdmissionService(JdbcSourceAdmission store, SourceIdentityMappingService identities,
                                  SharedWriteExecutor writes, SourceAdmissionPolicy policy,
                                  EvidenceStore evidence, ActionReceipts receipts, Clock clock) {
        if (store == null || identities == null || writes == null || policy == null
                || evidence == null || receipts == null || clock == null) {
            throw new IllegalArgumentException("source admission dependencies required");
        }
        this.store = store;
        this.identities = identities;
        this.writes = writes;
        this.policy = policy;
        this.evidence = evidence;
        this.receipts = receipts;
        this.clock = clock;
    }

    public Decision create(Actor actor, SourceCreate command, ActionContext action) {
        owner(actor);
        if (command == null || !validId(command.id()) || !validName(command.name())
                || !CATEGORIES.contains(command.category())) throw new IllegalArgumentException("invalid source create");
        String draft = policy.validatedConfigJson(command.config());
        return writes.execute(action, receipts::committed, ignored -> {
            if (store.locked(command.id()) != null) throw new AdmissionDenied();
            String sourceKind = "news_tech".equals(command.category()) ? "article" : "catalog";
            store.create(command.id(), sourceKind, command.name(), command.category(), draft, clock.instant());
            return record(action, current(command.id()), "created");
        });
    }

    public Decision edit(Actor actor, String sourceId, SourceWrite command, ActionContext action) {
        owner(actor);
        if (!validId(sourceId) || command == null || !validName(command.name())
                || !CATEGORIES.contains(command.category())) throw new IllegalArgumentException("invalid source edit");
        String draft = policy.validatedConfigJson(command.config());
        return writes.execute(action, receipts::committed, ignored -> {
            Row old = expected(sourceId, command.expectedRevision());
            if (old.category() != null && !old.category().equals(command.category())) {
                throw new AdmissionDenied();
            }
            if (!store.edit(old, command.name(), command.category(), draft, clock.instant())) {
                throw new AdmissionDenied();
            }
            return record(action, current(sourceId), "edited");
        });
    }

    /** Preparation only; B06 must recheck and persist the actual source_runs probe. */
    public ProbeTicket requestProbe(Actor actor, String sourceId, long expectedRevision,
                                    ActionContext action) {
        owner(actor);
        if (!validId(sourceId)) throw new IllegalArgumentException("invalid source id");
        return writes.execute(action, key -> null, ignored -> {
            Row row = expected(sourceId, expectedRevision);
            if (!"disabled".equals(row.activationStage()) || row.draftConfigJson() == null) {
                throw new AdmissionDenied();
            }
            return new ProbeTicket(row.id(), row.configRevision(),
                    SourceIdentityMappingService.sha256(row.draftConfigJson()),
                    parserVersion(row.draftConfigJson()));
        });
    }

    public Decision startTrial(Actor actor, String sourceId, long expectedRevision,
                               ActionContext action) {
        owner(actor);
        if (!validId(sourceId)) throw new IllegalArgumentException("invalid source id");
        return writes.execute(action, receipts::committed, ignored -> {
            Row row = expected(sourceId, expectedRevision);
            if (!"disabled".equals(row.activationStage()) || row.draftConfigJson() == null) {
                throw new AdmissionDenied();
            }
            String hash = SourceIdentityMappingService.sha256(row.draftConfigJson());
            ProbeEvidence probe = evidence.probe(sourceId, row.configRevision());
            if (!policy.validProbe(probe, row.configRevision(), hash,
                    parserVersion(row.draftConfigJson()))) throw new AdmissionDenied();
            Instant validUntil = validUntil(row.draftConfigJson());
            if (!validUntil.isAfter(clock.instant())
                    || !store.setTrial(row, validUntil, clock.instant())) throw new AdmissionDenied();
            return record(action, current(sourceId), "trial_started");
        });
    }

    public Decision promote(Actor actor, String sourceId, long expectedRevision,
                            ActionContext action) {
        owner(actor);
        if (!validId(sourceId)) throw new IllegalArgumentException("invalid source id");
        return writes.execute(action, receipts::committed, ignored -> {
            Row row = expected(sourceId, expectedRevision);
            identities.writerIdentity(sourceId);
            if (!"trial".equals(row.activationStage()) || row.draftConfigJson() == null
                    || row.effectiveConfigJson() == null || row.configValidUntil() == null
                    || !row.configValidUntil().isAfter(clock.instant())
                    || !sameConfig(row.draftConfigJson(), row.effectiveConfigJson())) {
                throw new AdmissionDenied();
            }
            PromotionEvidence proof = evidence.promotion(sourceId, row.configRevision());
            if (!policy.validPromotion(proof, row.configRevision(),
                    SourceIdentityMappingService.sha256(row.draftConfigJson()),
                    parserVersion(row.draftConfigJson()))
                    || !store.promote(row, clock.instant())) throw new AdmissionDenied();
            return record(action, current(sourceId), "promoted");
        });
    }

    public Decision disable(Actor actor, String sourceId, long expectedRevision,
                            ActionContext action) {
        owner(actor);
        if (!validId(sourceId)) throw new IllegalArgumentException("invalid source id");
        return writes.execute(action, receipts::committed, ignored -> {
            Row row = expected(sourceId, expectedRevision);
            if (!"disabled".equals(row.activationStage()) && !store.disable(row, clock.instant())) {
                throw new AdmissionDenied();
            }
            return record(action, current(sourceId), "disabled");
        });
    }

    private Decision record(ActionContext action, Row row, String name) {
        Decision decision = new Decision(row.id(), row.revision(), row.configRevision(),
                row.activationStage(), name);
        receipts.record(action.actionKey(), decision);
        return decision;
    }
    private Row current(String sourceId) {
        Row row = store.locked(sourceId);
        if (row == null) throw new AdmissionDenied();
        return row;
    }
    private Row expected(String sourceId, long revision) {
        Row row = current(sourceId);
        if (row.revision() != revision) throw new AdmissionDenied();
        return row;
    }
    private static String parserVersion(String json) {
        return CanonicalJson.parse(json).get("parser_version").asText();
    }
    private static Instant validUntil(String json) {
        return Instant.parse(CanonicalJson.parse(json).get("valid_until").asText());
    }
    private static boolean sameConfig(String left, String right) {
        JsonNode a = CanonicalJson.parse(left);
        JsonNode b = CanonicalJson.parse(right);
        return java.util.Arrays.equals(CanonicalJson.bytes(a), CanonicalJson.bytes(b));
    }
    private static void owner(Actor actor) {
        if (actor == null || actor.userId() == null || !"owner".equals(actor.role())) {
            throw new AdmissionDenied();
        }
    }
    private static boolean validId(String id) {
        if (id == null || id.isBlank()) return false;
        try { WireValues.id(id, true); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static boolean validName(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 200
                || value.codePoints().anyMatch(Character::isISOControl)) return false;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(unit)) return false;
        }
        return true;
    }
}
