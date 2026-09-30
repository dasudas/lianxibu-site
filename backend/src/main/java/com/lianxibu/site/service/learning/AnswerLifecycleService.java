package com.lianxibu.site.service.learning;

import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/** Independent question-answer lifecycle; the question and package keep their own visibility. */
public final class AnswerLifecycleService {
    public enum Action { APPROVE, REJECT, PUBLISH, WITHDRAW }
    public record Current(String packageId, String questionId, String answerText,
                          String answerState, long revision, Long answerRevision,
                          byte[] reviewHash, boolean packagePublished,
                          boolean questionVisible) { }
    public record Result(String packageId, String questionId, long revision,
                         long answerRevision, String answerState, boolean replayed) { }
    public interface Store {
        Result committed(String actionKey, String commandHash);
        /** Lock the full package/question composite identity under B04's shared entry lock. */
        Current locked(String packageId, String questionId);
        /** Public read must acquire a consistent snapshot before evaluating visibility. */
        Current publicSnapshot(String packageId, String questionId);
        void edit(Current row, String answer, long nextAnswerRevision,
                  String commandHash, String actionKey);
        void transition(Current row, String state, byte[] reviewHash,
                        String commandHash, String actionKey);
    }
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }

    private final SharedWriteExecutor writes;
    private final Store store;

    public AnswerLifecycleService(SharedWriteExecutor writes, Store store) {
        if (writes == null || store == null) throw new IllegalArgumentException("answer dependencies required");
        this.writes = writes;
        this.store = store;
    }

    public Result edit(String packageId, String questionId, long expectedRevision,
                       String answer, ActionContext action) {
        key(packageId, questionId, expectedRevision, action);
        if (answer == null || answer.codePointCount(0, answer.length()) > 50_000)
            throw new IllegalArgumentException("invalid answer");
        String normalized = normalize(answer);
        String hash = commandHash("edit", packageId, questionId, expectedRevision, normalized);
        return execute(action, hash, () -> {
            Current row = current(packageId, questionId, expectedRevision);
            long nextBody = row.answerRevision() == null ? 1 : row.answerRevision() + 1;
            store.edit(row, normalized, nextBody, hash, action.actionKey());
            return new Result(packageId, questionId, expectedRevision + 1,
                    nextBody, "draft", false);
        });
    }

    public Result action(String packageId, String questionId, long expectedRevision,
                         Action requested, String reason, ActionContext action) {
        key(packageId, questionId, expectedRevision, action);
        if (requested == null || reason == null || reason.isBlank() || reason.length() > 2048)
            throw new IllegalArgumentException("invalid answer action");
        String hash = commandHash(requested.name(), packageId, questionId,
                expectedRevision, reason);
        return execute(action, hash, () -> {
            Current row = current(packageId, questionId, expectedRevision);
            String state = row.answerState();
            byte[] reviewed = null;
            switch (requested) {
                case APPROVE -> {
                    if (state == null || !Set.of("draft", "approved", "withdrawn").contains(state)
                            || row.answerText() == null || row.answerText().isBlank())
                        throw new Rejected("answer_not_reviewable");
                    reviewed = answerHash(row.answerText());
                    state = "approved";
                }
                case PUBLISH -> {
                    if (!"approved".equals(state) || row.answerText() == null
                            || row.reviewHash() == null
                            || !Arrays.equals(row.reviewHash(), answerHash(row.answerText())))
                        throw new Rejected("review_required");
                    reviewed = row.reviewHash();
                    state = "published";
                }
                case REJECT -> {
                    if (state == null || !Set.of("draft", "approved").contains(state))
                        throw new Rejected("invalid_answer_state");
                    state = "draft";
                }
                case WITHDRAW -> {
                    if (state == null || !Set.of("approved", "published").contains(state))
                        throw new Rejected("invalid_answer_state");
                    state = "withdrawn";
                }
            }
            store.transition(row, state, reviewed, hash, action.actionKey());
            return new Result(packageId, questionId, expectedRevision + 1,
                    row.answerRevision() == null ? 0 : row.answerRevision(), state, false);
        });
    }

    /** Requires package, question, answer state and current reviewed bytes in one read snapshot. */
    public String publicAnswer(String packageId, String questionId) {
        if (!validId(packageId) || !validId(questionId)) throw new IllegalArgumentException("invalid answer key");
        Current row = store.publicSnapshot(packageId, questionId);
        if (row == null || !row.packagePublished() || !row.questionVisible()
                || !"published".equals(row.answerState()) || row.answerText() == null
                || row.reviewHash() == null) return null;
        return Arrays.equals(row.reviewHash(), answerHash(row.answerText())) ? row.answerText() : null;
    }

    private Result execute(ActionContext action, String hash, java.util.function.Supplier<Result> work) {
        Result existing = store.committed(action.actionKey(), hash);
        if (existing != null) return replay(existing);
        Result result = writes.execute(action, key -> store.committed(key, hash), ignored -> work.get());
        return result;
    }

    private static Result replay(Result old) {
        return new Result(old.packageId(), old.questionId(), old.revision(),
                old.answerRevision(), old.answerState(), true);
    }

    private Current current(String packageId, String questionId, long expectedRevision) {
        Current row = store.locked(packageId, questionId);
        if (row == null) throw new Rejected("question_missing");
        if (row.revision() != expectedRevision) throw new Rejected("revision_conflict");
        return row;
    }

    private static void key(String packageId, String questionId, long revision, ActionContext action) {
        if (!validId(packageId) || !validId(questionId) || revision < 1 || action == null)
            throw new IllegalArgumentException("invalid answer command");
    }
    private static boolean validId(String value) {
        return value != null && !value.isBlank() && value.length() <= 64;
    }
    private static String normalize(String answer) {
        return answer.replace("\r\n", "\n").replace('\r', '\n');
    }
    public static byte[] answerHash(String answer) {
        if (answer == null) throw new IllegalArgumentException("answer required");
        return digest("learning-answer-v1\n" + normalize(answer));
    }
    private static String commandHash(String operation, String packageId, String questionId,
                                      long expectedRevision, String value) {
        return HexFormat.of().formatHex(digest("answer-command-v1\n" + operation.length() + ":" + operation
                + packageId.length() + ":" + packageId + questionId.length() + ":" + questionId
                + expectedRevision + ":" + value.length() + ":" + value));
    }
    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
