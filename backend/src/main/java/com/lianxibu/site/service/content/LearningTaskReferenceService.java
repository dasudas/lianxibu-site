package com.lianxibu.site.service.content;

import com.lianxibu.site.service.content.ContentService.LearningTask;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.learning.LearningService;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A daily report references one current public learning object by its complete package key. */
public final class LearningTaskReferenceService {
    public record Link(String packageId, String objectKind, String objectId) { }
    public record LinkStatus(boolean available, Link link, String message) { }
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }

    private static final Set<String> KINDS = Set.of("node", "question", "project");
    private final LearningService learning;
    private final TransactionTemplate reads;

    public LearningTaskReferenceService(LearningService learning, PlatformTransactionManager tx) {
        if (learning == null || tx == null) throw new IllegalArgumentException("learning reference dependencies required");
        this.learning = learning;
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Called before saving a new report body; empty candidates produce JSON null. */
    public LearningTask prepare(String reportType, List<LearningTask> candidates) {
        if (reportType == null || candidates == null) throw new IllegalArgumentException("report candidate input required");
        if (candidates.size() > 1) throw new Rejected("too_many_learning_tasks");
        if (candidates.isEmpty()) return null;
        if (!"daily".equals(reportType)) throw new Rejected("learning_task_daily_only");
        LearningTask proposed = candidates.getFirst();
        key(proposed);
        if (proposed.reason() == null || proposed.reason().isBlank()
                || proposed.reason().length() > 2048) throw new Rejected("invalid_learning_reason");
        return reads.execute(status -> {
            String title;
            try { title = publicTitle(proposed); }
            catch (IdentityService.Rejected unavailable) {
                throw new Rejected("learning_candidate_unavailable");
            }
            if (title == null || title.isBlank())
                throw new Rejected("learning_candidate_unavailable");
            return new LearningTask(proposed.packageId(), proposed.objectKind(), proposed.objectId(),
                    title, proposed.reason());
        });
    }

    /** Recheck at link rendering and navigation; revoked content never changes the report body. */
    public LinkStatus linkStatus(LearningTask frozen) {
        key(frozen);
        return reads.execute(status -> {
            try {
                if (publicTitle(frozen) == null)
                    return new LinkStatus(false, null, "learning_unavailable");
                return new LinkStatus(true,
                        new Link(frozen.packageId(), frozen.objectKind(), frozen.objectId()), null);
            } catch (IdentityService.Rejected unavailable) {
                return new LinkStatus(false, null, "learning_unavailable");
            }
        });
    }

    private String publicTitle(LearningTask task) {
        return switch (task.objectKind()) {
            case "node" -> learning.node(task.packageId(), task.objectId()).title();
            case "question" -> learning.question(task.packageId(), task.objectId()).title();
            case "project" -> learning.project(task.packageId(), task.objectId()).title();
            default -> throw new Rejected("invalid_learning_reference");
        };
    }

    private static void key(LearningTask task) {
        if (task == null || task.packageId() == null || task.packageId().isBlank()
                || task.packageId().length() > 64 || !KINDS.contains(task.objectKind())
                || task.objectId() == null || task.objectId().isBlank()
                || task.objectId().length() > 64)
            throw new Rejected("invalid_learning_reference");
    }
}
