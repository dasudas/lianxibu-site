package com.lianxibu.site.service.records;

import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Per-user current record writer; learning and article identities remain disjoint. */
public final class PrivateRecordWriteService implements RecordsService {
    public record Key(String userId, String packageId, String kind, String objectId) { }
    public record Current(long revision, String status, String assessment, String note,
                          String artifactUrl, String responseText, Long confirmedBodyRevision,
                          boolean read, boolean favorite, boolean ignored) { }
    public record Value(String status, String assessment, String note, String artifactUrl,
                        String responseText, long confirmedBodyRevision,
                        boolean read, boolean favorite, boolean ignored, Instant updatedAt) { }
    public record Progress(long completed, long eligible, String rate) { }
    public interface Store {
        Record committed(String actionKey, String commandHash);
        Current currentForUpdate(Key key);
        /** Null for missing, hidden or disabled object; must bind full package/kind/id. */
        Long visibleBodyRevision(String packageId, String kind, String objectId);
        void insert(Key key, Value value, String actionKey, String commandHash);
        void update(Key key, long expectedRevision, Value value,
                    String actionKey, String commandHash);
        /** Active visible route nodes; may contain shared/duplicate IDs. */
        List<String> routeNodes(String packageId, String routeId);
        Set<String> completedNodes(String userId, String packageId, Set<String> nodeIds);
    }
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }

    private static final Set<String> KINDS = Set.of("article", "node", "question", "project");
    private static final Set<String> STATUSES = Set.of("not_started", "planned", "learning", "completed");
    private static final Set<String> ASSESSMENTS = Set.of("unassessed", "not_mastered", "partial", "mastered");
    private final Store store;
    private final RecordReadService reads;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PrivateRecordWriteService(Store store, RecordReadService reads,
                                     PlatformTransactionManager manager, Clock clock) {
        if (store == null || reads == null || manager == null || clock == null)
            throw new IllegalArgumentException("record dependencies required");
        this.store = store;
        this.reads = reads;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public RecordPage list(Actor actor, String packageId, String kind,
                                     long page, int pageSize) {
        return reads.list(actor, packageId, kind, page, pageSize);
    }

    @Override public Record write(Actor actor, String kind, String id, RecordWrite command,
                                  String actionKey, String csrf, String origin) {
        String userId = user(actor);
        Key key = key(userId, kind, command == null ? null : command.packageId(), id);
        if (command == null || !kind.equals(command.objectKind())
                || command.expectedRevision() < 0 || !STATUSES.contains(command.status())
                || !ASSESSMENTS.contains(command.selfAssessment())
                || actionKey == null || actionKey.isBlank() || actionKey.length() > 128
                || csrf == null || csrf.isBlank() || origin == null || origin.isBlank())
            throw new IllegalArgumentException("invalid record write");
        String note = normalize(command.note());
        if (note.codePointCount(0, note.length()) > 10_000) throw new Rejected("note_too_long");
        boolean article = "article".equals(kind);
        if (article && (command.isRead() == null || command.isFavorite() == null
                || command.isIgnored() == null) || !article && (command.isRead() != null
                || command.isFavorite() != null || command.isIgnored() != null))
            throw new IllegalArgumentException("invalid article flags");
        String artifact = command.artifactUrl();
        String response = null;
        if ("question".equals(kind)) {
            if (command.latestAnswerText() == null || command.latestSubmissionUrl() != null)
                throw new IllegalArgumentException("invalid question response");
            response = normalize(command.latestAnswerText());
            if (response.codePointCount(0, response.length()) > 50_000)
                throw new Rejected("answer_too_long");
        } else if ("project".equals(kind)) {
            if (command.latestAnswerText() != null) throw new IllegalArgumentException("invalid project response");
            // Separate latest submission from the general artifact link in the existing response_text column.
            response = command.latestSubmissionUrl();
        } else if (command.latestAnswerText() != null || command.latestSubmissionUrl() != null) {
            throw new IllegalArgumentException("unexpected response");
        }
        String hash = commandHash(key, command, note, response);
        Record prior = store.committed(actionKey, hash);
        if (prior != null) return prior;
        final String finalResponse = response;
        try {
            return tx.execute(status -> {
                Record duplicate = store.committed(actionKey, hash);
                if (duplicate != null) return duplicate;
                Long bodyRevision = store.visibleBodyRevision(key.packageId(), kind, id);
                if (bodyRevision == null) throw new Rejected("object_missing");
                Current current = store.currentForUpdate(key);
                if (current == null && command.expectedRevision() != 0
                        || current != null && current.revision() != command.expectedRevision())
                    throw new Rejected("revision_conflict");
                Value value = new Value(command.status(), command.selfAssessment(), note,
                        artifact, finalResponse, bodyRevision,
                        article && command.isRead(), article && command.isFavorite(),
                        article && command.isIgnored(), clock.instant());
                if (current == null) store.insert(key, value, actionKey, hash);
                else store.update(key, current.revision(), value, actionKey, hash);
                return project(key, command.expectedRevision() + 1, value);
            });
        } catch (TransactionException uncertain) {
            Record committed = store.committed(actionKey, hash);
            if (committed != null) return committed;
            throw uncertain;
        }
    }

    /** Shared nodes count once per route; answer/submission records do not imply completion. */
    public Progress progress(Actor actor, String packageId, String routeId) {
        String userId = user(actor);
        if (!id(packageId) || !id(routeId)) throw new IllegalArgumentException("invalid route");
        Set<String> eligible = new HashSet<>(store.routeNodes(packageId, routeId));
        if (eligible.contains(null) || eligible.stream().anyMatch(value -> !id(value)))
            throw new Rejected("invalid_route_nodes");
        if (eligible.isEmpty()) return new Progress(0, 0, null);
        Set<String> completed = store.completedNodes(userId, packageId, Set.copyOf(eligible));
        if (completed == null) throw new Rejected("progress_unavailable");
        Set<String> matched = new HashSet<>(completed);
        matched.retainAll(eligible);
        long count = matched.size();
        String rate = BigDecimal.valueOf(count).divide(BigDecimal.valueOf(eligible.size()),
                8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return new Progress(count, eligible.size(), rate);
    }

    private static Record project(Key key, long revision, Value value) {
        String kind = key.kind();
        return new Record("article".equals(kind) ? null : key.packageId(), kind, key.objectId(),
                revision, value.status(), value.assessment(), value.note(), value.artifactUrl(),
                "question".equals(kind) ? value.responseText() : null,
                "project".equals(kind) ? value.responseText() : null,
                value.confirmedBodyRevision(), value.updatedAt(), value.read(),
                value.favorite(), value.ignored());
    }
    private static Key key(String user, String kind, String packageId, String objectId) {
        if (!KINDS.contains(kind) || !id(objectId)
                || "article".equals(kind) && packageId != null
                || !"article".equals(kind) && !id(packageId))
            throw new IllegalArgumentException("invalid record key");
        return new Key(user, "article".equals(kind) ? "" : packageId, kind, objectId);
    }
    private static String user(Actor actor) {
        if (actor == null || !id(actor.userId()))
            throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED, null);
        return actor.userId();
    }
    private static boolean id(String value) {
        if (value == null || value.isBlank()) return false;
        try { com.lianxibu.site.domain.protocol.WireValues.id(value,false); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("required text");
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }
    private static String commandHash(Key key, RecordWrite command, String note, String response) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("record-command-v1\n".getBytes(StandardCharsets.UTF_8));
            field(digest, key.userId());
            field(digest, key.packageId());
            field(digest, key.kind());
            field(digest, key.objectId());
            field(digest, Long.toString(command.expectedRevision()));
            field(digest, command.status());
            field(digest, command.selfAssessment());
            field(digest, note);
            field(digest, command.artifactUrl());
            field(digest, response);
            field(digest, String.valueOf(command.isRead()));
            field(digest, String.valueOf(command.isFavorite()));
            field(digest, String.valueOf(command.isIgnored()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private static void field(MessageDigest digest, String value) {
        if (value == null) { digest.update((byte) 0); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
