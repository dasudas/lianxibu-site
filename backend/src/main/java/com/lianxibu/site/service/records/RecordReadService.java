package com.lianxibu.site.service.records;

import com.lianxibu.site.repository.records.JdbcRecordReads;
import com.lianxibu.site.repository.records.JdbcRecordReads.Row;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-scoped current record reader; no legacy attempt history is synthesized. */
public final class RecordReadService implements RecordsService {
    private static final Set<String> KINDS = Set.of("article", "node", "question", "project");
    private final JdbcRecordReads store;
    private final TransactionTemplate reads;

    public RecordReadService(JdbcRecordReads store, PlatformTransactionManager tx) {
        if (store == null || tx == null) throw new IllegalArgumentException("record read dependencies required");
        this.store = store;
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Optional<Record> current(Actor actor, String kind, String packageId, String objectId) {
        String userId = user(actor);
        String packageKey = packageKey(kind, packageId);
        id(objectId);
        return reads.execute(status -> Optional.ofNullable(store.current(userId, kind, packageKey, objectId))
                .map(RecordReadService::project));
    }

    @Override public RecordPage list(Actor actor, String packageId, String kind,
                                     long page, int pageSize) {
        String userId = user(actor);
        if (page < 1 || pageSize < 1 || pageSize > 50 || page > Long.MAX_VALUE / pageSize) {
            throw new IllegalArgumentException("invalid page");
        }
        if (kind != null && !KINDS.contains(kind)) throw new IllegalArgumentException("invalid record kind");
        if (packageId != null) id(packageId);
        if (packageId == null && kind != null && !kind.equals("article")) {
            return new RecordPage(java.util.List.of(), page, pageSize, 0);
        }
        if (packageId != null && "article".equals(kind)) {
            return new RecordPage(java.util.List.of(), page, pageSize, 0);
        }
        String key = packageId == null ? "" : packageId;
        return reads.execute(status -> {
            var result = store.rows(userId, key, kind, page, pageSize);
            return new RecordPage(result.items().stream().map(RecordReadService::project).toList(),
                    page, pageSize, result.total());
        });
    }

    private static Record project(Row row) {
        String kind = row.objectKind();
        String latestAnswer = "question".equals(kind) ? row.responseText() : null;
        String latestSubmission = "project".equals(kind) ? row.artifactUrl() : null;
        boolean article = "article".equals(kind);
        return new Record("article".equals(kind) ? null : row.packageId(), kind, row.objectId(),
                row.revision(), row.status().isEmpty() ? "not_started" : row.status(),
                row.selfAssessment(), row.note(), row.artifactUrl(), latestAnswer,
                latestSubmission, row.confirmedBodyRevision(), row.updatedAt(),
                article && row.isRead(), article && row.isFavorite(), article && row.isIgnored());
    }

    private static String user(Actor actor) {
        if (actor == null || actor.userId() == null || actor.userId().isBlank()) {
            throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED, null);
        }
        return actor.userId();
    }
    private static String packageKey(String kind, String packageId) {
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("invalid record kind");
        if ("article".equals(kind)) {
            if (packageId != null) throw new IllegalArgumentException("article package must be null");
            return "";
        }
        id(packageId);
        return packageId;
    }
    private static void id(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("record key required");
        }
    }

    @Override public Record write(Actor actor, String kind, String id, RecordWrite command,
                                  String key, String csrf, String origin) {
        throw new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE, "1");
    }
}
