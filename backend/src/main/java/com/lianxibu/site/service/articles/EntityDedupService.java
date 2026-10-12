package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.articles.JdbcEntityDedup;
import com.lianxibu.site.repository.articles.JdbcEntityDedup.Entity;
import com.lianxibu.site.repository.articles.JdbcEntityDedup.Member;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Applies validated same-category entity decisions and independent occurrence ownership. */
public final class EntityDedupService {
    public interface DispositionLog {
        Decision committed(String actionKey, String requestHash);
        void append(String actionKey, String requestHash, Decision result,
                    String operation, String itemId, String oldEntityId,
                    String newEntityId, String reason);
    }
    public record Automatic(String itemId, long expectedItemRevision, String decision,
                            String category, String identityKey, String proposedEntityId,
                            List<String> candidateEntityIds, String title, String summary,
                            String reason) { }
    public record Decision(String itemId, String entityId, String state,
                           boolean newContribution, Long occurrenceCount) { }
    public static final class Rejected extends RuntimeException {
        public Rejected() { super("entity decision identity or version rejected"); }
    }
    private static final Set<String> CATEGORY = Set.of("news_tech", "agent", "skill", "workflow", "model");
    private static final Set<String> AUTOMATIC = Set.of("new", "existing", "needs_review");
    private final JdbcEntityDedup store;
    private final SharedWriteExecutor writes;
    private final DispositionLog log;
    private final Clock clock;

    public EntityDedupService(JdbcEntityDedup store, SharedWriteExecutor writes,
                              DispositionLog log, Clock clock) {
        if (store == null || writes == null || log == null || clock == null)
            throw new IllegalArgumentException("dedup dependencies required");
        this.store = store;
        this.writes = writes;
        this.log = log;
        this.clock = clock;
    }

    public Decision applyAutomatic(Automatic value, ActionContext action) {
        validateAutomatic(value);
        String requestHash = automaticHash(value);
        return writes.execute(action, key -> log.committed(key, requestHash), context -> applyAutomaticInTransaction(value, context));
    }

    /** Joins the caller's B04 transaction, so result retention, all members and downstream work commit together. */
    public Decision applyAutomaticInTransaction(Automatic value, ActionContext context) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("automatic dedup requires shared write transaction");
        if (context == null) throw new Rejected();
        validateAutomatic(value);
        String requestHash = automaticHash(value);
        Decision committed = log.committed(context.actionKey(), requestHash);
        if (committed != null) return committed;
        return applyAutomaticCurrent(value, context, requestHash);
    }

    /** Pure validated original business identity, shared with independent original-input readers. */
    public static String automaticFingerprint(Automatic value){validateAutomatic(value);return automaticHash(value);}

    private static String automaticHash(Automatic value) {
        return fingerprint("automatic", value.itemId(), value.expectedItemRevision(),
                value.decision(), value.category(), value.identityKey(), value.proposedEntityId(),
                value.candidateEntityIds(), value.title(), value.summary(), value.reason());
    }

    private Decision applyAutomaticCurrent(Automatic value, ActionContext context, String requestHash) {
        Member item = requiredMember(value.itemId(), value.expectedItemRevision());
        requireEvidence(item);
        if (item.entityId() != null) throw new Rejected();
        if (!value.category().equals(item.category())) throw new Rejected();
        if ("needs_review".equals(value.decision())) {
            store.setReview(item);
            Decision result = new Decision(item.id(), null, "needs_review", false, null);
            log.append(context.actionKey(), requestHash, result, "needs_review", item.id(),
                    null, null, value.reason());
            return result;
        }
        Entity entity;
        boolean created = false;
        if ("new".equals(value.decision())) {
            entity = store.byIdentity(value.category(), value.identityKey());
            if (entity == null) {
                store.insertEntity(context.preallocatedResourceId(), value.category(),
                        value.identityKey(), value.title(), value.summary(),
                        item.businessDate(), clock.instant());
                entity = store.entity(context.preallocatedResourceId());
                created = true;
            }
        } else {
            if (!value.candidateEntityIds().contains(value.proposedEntityId())) throw new Rejected();
            entity = store.entity(value.proposedEntityId());
            if (entity == null) throw new Rejected();
            Entity byIdentity = store.byIdentity(value.category(), value.identityKey());
            if (byIdentity != null && !byIdentity.id().equals(entity.id())) throw new Rejected();
        }
        if (entity == null || !value.category().equals(entity.category())
                || entity.legacy() && entity.occurrenceCount() == null) throw new Rejected();
        Decision result = assign(item, entity.id(), created, value.decision(), value.reason());
        log.append(context.actionKey(), requestHash, result, value.decision(), item.id(),
                null, entity.id(), value.reason());
        return result;
    }

    public Decision associate(String itemId, long expectedItemRevision, String entityId,
                              String reason, ActionContext action) {
        requireReason(reason);
        String requestHash = fingerprint("associate", itemId, expectedItemRevision, entityId, reason);
        return writes.execute(action, key -> log.committed(key, requestHash), context -> {
            Member item = requiredMember(itemId, expectedItemRevision);
            requireEvidence(item);
            if (item.entityId() != null) throw new Rejected();
            Entity entity = store.entity(entityId);
            if (entity == null || !entity.category().equals(item.category())
                    || entity.legacy() && entity.occurrenceCount() == null) throw new Rejected();
            Decision result = assign(item, entity.id(), false, "manual_associate", reason);
            log.append(context.actionKey(), requestHash, result, "associate", item.id(),
                    null, entity.id(), reason);
            return result;
        });
    }

    public Decision createManual(String itemId, long expectedItemRevision, String category,
                                 String identityKey, String title, String summary,
                                 String reason, ActionContext action) {
        requireReason(reason);
        if (!CATEGORY.contains(category) || !validText(identityKey, 4096)
                || !validText(title, 512)) throw new Rejected();
        String requestHash = fingerprint("manual_create", itemId, expectedItemRevision,
                category, identityKey, title, summary, reason);
        return writes.execute(action, key -> log.committed(key, requestHash), context -> {
            Member item = requiredMember(itemId, expectedItemRevision);
            requireEvidence(item);
            if (item.entityId() != null || !category.equals(item.category())
                    || store.byIdentity(category, identityKey) != null) throw new Rejected();
            store.insertEntity(context.preallocatedResourceId(), category, identityKey, title,
                    summary, item.businessDate(), clock.instant());
            Decision result = assign(item, context.preallocatedResourceId(), true,
                    "manual_create", reason);
            log.append(context.actionKey(), requestHash, result, "manual_create", item.id(), null,
                    context.preallocatedResourceId(), reason);
            return result;
        });
    }

    public Decision exclude(String itemId, long expectedItemRevision, String reason,
                            ActionContext action) {
        requireReason(reason);
        String requestHash = fingerprint("exclude", itemId, expectedItemRevision, reason);
        return writes.execute(action, key -> log.committed(key, requestHash), context -> {
            Member item = requiredMember(itemId, expectedItemRevision);
            if (item.entityId() == null) throw new Rejected();
            store.clearContribution(item.id());
            store.exclude(item);
            store.repairContribution(item.sourceId(), item.sourceItemId(), item.entityId());
            store.recompute(item.entityId());
            store.recomputeFirstSeenAfterCorrection(item.entityId());
            Entity changed = store.entity(item.entityId());
            Decision result = new Decision(item.id(), item.entityId(), "excluded", false,
                    changed.occurrenceCount());
            log.append(context.actionKey(), requestHash, result, "exclude", item.id(),
                    item.entityId(), null, reason);
            return result;
        });
    }

    public Decision split(String itemId, long expectedItemRevision, String newIdentityKey,
                          String title, String summary, String reason, ActionContext action) {
        requireReason(reason);
        if (!validText(newIdentityKey, 4096) || !validText(title, 512)) throw new Rejected();
        String requestHash = fingerprint("split", itemId, expectedItemRevision,
                newIdentityKey, title, summary, reason);
        return writes.execute(action, key -> log.committed(key, requestHash), context -> {
            Member item = requiredMember(itemId, expectedItemRevision);
            requireEvidence(item);
            if (item.entityId() == null) throw new Rejected();
            Entity old = store.entity(item.entityId());
            if (old == null || !old.category().equals(item.category())) throw new Rejected();
            Entity next = store.byIdentity(item.category(), newIdentityKey);
            if (next == null) {
                store.insertEntity(context.preallocatedResourceId(), item.category(), newIdentityKey,
                        title, summary, item.businessDate(), clock.instant());
                next = store.entity(context.preallocatedResourceId());
            }
            if (next.id().equals(old.id())) throw new Rejected();
            store.clearContribution(item.id());
            store.reassign(item, next.id());
            store.repairContribution(item.sourceId(), item.sourceItemId(), old.id());
            byte[] occurrence = JdbcEntityDedup.occurrenceHash(item.sourceId(),
                    item.sourceItemId(), next.id());
            boolean fresh = !store.contributionExists(occurrence);
            if (fresh) store.setContribution(item.id(), occurrence);
            store.recompute(old.id());
            store.recompute(next.id());
            store.recomputeFirstSeenAfterCorrection(old.id());
            store.recomputeFirstSeenAfterCorrection(next.id());
            Decision result = new Decision(item.id(), next.id(), "ready", fresh,
                    store.entity(next.id()).occurrenceCount());
            log.append(context.actionKey(), requestHash, result, "split", item.id(),
                    old.id(), next.id(), reason);
            return result;
        });
    }

    private Decision assign(Member item, String entityId, boolean newEntity,
                            String decision, String reason) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("decision", decision);
        value.put("entity_id", entityId);
        value.put("reason", reason);
        store.assign(item, entityId, newEntity,
                new String(CanonicalJson.bytes(value), StandardCharsets.UTF_8));
        byte[] occurrence = JdbcEntityDedup.occurrenceHash(item.sourceId(), item.sourceItemId(), entityId);
        boolean fresh = !store.contributionExists(occurrence);
        if (fresh) store.setContribution(item.id(), occurrence);
        store.recompute(entityId);
        return new Decision(item.id(), entityId, "ready", fresh,
                store.entity(entityId).occurrenceCount());
    }
    private Member requiredMember(String id, long expectedRevision) {
        Member item = store.member(id);
        if (item == null || item.revision() != expectedRevision) throw new Rejected();
        return item;
    }
    private static void requireEvidence(Member item) {
        if (!"accepted".equals(item.terminalState()) || !item.effectiveValid()
                || "excluded".equals(item.state())
                || "needs_review".equals(item.projectionDecision())
                || item.sourceItemId() == null || item.sourceItemId().isBlank()) throw new Rejected();
    }
    private static void validateAutomatic(Automatic value) {
        if (value == null || !AUTOMATIC.contains(value.decision()) || !CATEGORY.contains(value.category())
                || !validText(value.itemId(), 64) || !validText(value.identityKey(), 4096)
                || !validText(value.title(), 512) || value.candidateEntityIds() == null
                || value.candidateEntityIds().size() > 100 || value.candidateEntityIds().stream().distinct().count()
                    != value.candidateEntityIds().size()
                || value.expectedItemRevision() < 0) throw new Rejected();
        requireReason(value.reason());
    }
    private static void requireReason(String value) {
        if (!validText(value, 2048)) throw new Rejected();
    }
    private static boolean validText(String value, int limit) {
        return value != null && !value.isBlank()
                && value.codePointCount(0, value.length()) <= limit;
    }
    private static String fingerprint(String operation, Object... fields) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("operation", operation);
        ArrayNode array = value.putArray("fields");
        for (Object field : fields) {
            if (field == null) array.addNull();
            else if (field instanceof Number number) array.add(number.longValue());
            else if (field instanceof List<?> values) {
                ArrayNode sub = array.addArray();
                for (Object element : values) sub.add(String.valueOf(element));
            } else array.add(String.valueOf(field));
        }
        return CanonicalJson.hash(Domain.ACTION, value);
    }
}
