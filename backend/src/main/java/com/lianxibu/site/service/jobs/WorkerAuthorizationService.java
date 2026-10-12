package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;

/** Claims one persisted prepared attempt and authorizes exactly one network send. */
public final class WorkerAuthorizationService {
    public record Candidate(String rootId, String attemptId, int priority, Instant eligibleAt,
                            String rootState, String attemptState, boolean dependenciesReady,
                            int callCount, int maxAttempts, String frozenRequestJson,
                            String inputHash, long rootRevision) {
        @Override public String toString() {
            return "Candidate[rootId=" + rootId + ",priority=" + priority + "]";
        }
    }
    public record Identity(String rootId, String attemptId, String dispatchToken,
                           long slotRevision, long fencing, Instant leaseUntil,
                           String frozenRequestJson, String inputHash) {
        @Override public String toString() { return "Identity[attemptId=" + attemptId + "]"; }
    }
    public record Authorization(boolean send, String reason) { }
    public enum State { IDLE, CLAIMED, SENT, NOT_SENT, LOST, UNKNOWN }
    public record Claim(State state, Permit permit, String reason, Instant nextEligibleAt) { }
    public record Dispatch<T>(State state, T outcome) { }
    public interface Store {
        /** Called outside transactions. The returned scope must revalidate in every writer attempt. */
        default Store captureClaim(ActionContext action,Instant now){return this;}
        /** A separate outside capture for this exact send-authorization action. */
        default Store captureAuthorization(Identity identity){return this;}
        Identity committedClaim(String actionKey);
        Authorization committedAuthorization(String actionKey);
        Boolean committedAbort(String actionKey);
        boolean paused();
        boolean slotFree();
        List<Candidate> candidates(Instant now);
        /** Must reserve root budget and occupied slot with CAS in this exact transaction. */
        Identity reserve(Candidate chosen, String dispatchToken, Instant leaseUntil, Instant now,
                         String actionKey);
        /** Recheck slot, root, attempt, lease, fencing and settings under the entry lock. */
        Authorization authorize(Identity identity, Instant now, String actionKey);
        /** Only for this process's proof that HTTP was never called. */
        Boolean refundNotSent(Identity identity, Instant nextEligibleAt, String reason,
                              String actionKey);
    }
    @FunctionalInterface public interface Sender<T> {
        T sendOnce(String frozenRequestJson);
    }
    public static final class Permit {
        private final Identity identity;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Permit(Identity identity) { this.identity = identity; }
        public String attemptId() { return identity.attemptId(); }
        public Identity identity() { return identity; }
        public void cancelBeforeSend() { cancelled.set(true); }
    }
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final SharedWriteExecutor writes;
    private final Store store;
    private final Clock clock;
    private final IntUnaryOperator tieChoice;
    private final java.util.function.BooleanSupplier dispatchAllowed;
    private final AtomicReference<Permit> current = new AtomicReference<>();

    public WorkerAuthorizationService(SharedWriteExecutor writes, Store store, Clock clock) {
        this(writes, store, clock, bound -> ThreadLocalRandom.current().nextInt(bound), () -> false);
    }
    public WorkerAuthorizationService(SharedWriteExecutor writes, Store store, Clock clock,
                                      java.util.function.BooleanSupplier dispatchAllowed) {
        this(writes,store,clock,bound -> ThreadLocalRandom.current().nextInt(bound),dispatchAllowed);
    }
    WorkerAuthorizationService(SharedWriteExecutor writes, Store store, Clock clock,
                               IntUnaryOperator tieChoice) {
        this(writes,store,clock,tieChoice,() -> false);
    }
    WorkerAuthorizationService(SharedWriteExecutor writes, Store store, Clock clock,
                               IntUnaryOperator tieChoice, java.util.function.BooleanSupplier dispatchAllowed) {
        if (writes == null || store == null || clock == null || tieChoice == null || dispatchAllowed == null)
            throw new IllegalArgumentException("worker dependencies required");
        this.writes = writes;
        this.store = store;
        this.clock = clock;
        this.tieChoice = tieChoice;
        this.dispatchAllowed = dispatchAllowed;
    }

    public Claim claim(ActionContext action) {
        // Lease identity must equal the original UTC microsecond receipt on immediate replay.
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!inWindow(now)) return new Claim(State.IDLE, null, "outside_window", nextWindow(now));
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("worker must own outer transaction");
        Store capturedStore=Objects.requireNonNull(store.captureClaim(action,now));
        Identity identity = writes.execute(action, store::committedClaim, context -> {
            if (!inWindow(clock.instant()) || capturedStore.paused() || !capturedStore.slotFree())
                return idleMarker();
            if (!dispatchAllowed.getAsBoolean()) return idleMarker();
            List<Candidate> candidates = capturedStore.candidates(now).stream()
                    .filter(item -> item != null && item.priority() >= 1 && item.priority() <= 14
                            && item.eligibleAt() != null && !item.eligibleAt().isAfter(now)
                            && ("queued".equals(item.rootState()) || "waiting_retry".equals(item.rootState()))
                            && "prepared".equals(item.attemptState())
                            && item.dependenciesReady() && item.callCount() < item.maxAttempts()
                            && item.callCount() >= 0
                            && item.maxAttempts() <= 3 && item.maxAttempts() > 0
                            && item.inputHash() != null
                            && item.inputHash().matches("[0-9a-f]{64}")
                            && completeRequest(item.frozenRequestJson(), item.inputHash()))
                    .sorted(Comparator.comparingInt(Candidate::priority)).toList();
            if (candidates.isEmpty()) return idleMarker();
            int best = candidates.getFirst().priority();
            List<Candidate> same = candidates.stream()
                    .takeWhile(item -> item.priority() == best).toList();
            int index = tieChoice.applyAsInt(same.size());
            if (index < 0 || index >= same.size()) throw new IllegalStateException("tie choice invalid");
            return capturedStore.reserve(same.get(index), UUID.randomUUID().toString(),
                    now.plus(Duration.ofSeconds(150)), now, context.actionKey());
        });
        if (identity == null || identity.attemptId() == null)
            return new Claim(State.IDLE, null, "paused_busy_or_no_ready_task", now);
        Permit existing = current.get();
        if (existing != null && existing.identity.equals(identity))
            return new Claim(State.CLAIMED, existing, null, null);
        Permit permit = new Permit(identity);
        current.set(permit);
        return new Claim(State.CLAIMED, permit, null, null);
    }

    public <T> Dispatch<T> dispatch(Permit permit, Sender<T> sender) {
        if (permit == null || sender == null || current.get() != permit
                || !permit.consumed.compareAndSet(false, true))
            return new Dispatch(State.LOST, null);
        Identity identity = permit.identity;
        if (permit.cancelled.get()) return abortNeverSent(identity, "cancelled_before_send");
        Authorization authorization;
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("worker must own outer transaction");
            Store capturedStore=Objects.requireNonNull(store.captureAuthorization(identity));
            authorization = writes.execute(action(identity, "authorize"),
                    store::committedAuthorization, context -> {
                        Instant now = clock.instant();
                        if (!inWindow(now) || capturedStore.paused())
                            return new Authorization(false, "window_or_pause");
                        if (!dispatchAllowed.getAsBoolean()) return new Authorization(false,"operational_guard");
                        return capturedStore.authorize(identity, now, context.actionKey());
                    });
        } catch (RuntimeException uncertain) {
            return new Dispatch(State.UNKNOWN, null);
        }
        if (!authorization.send()) {
            if (Set.of("window_or_pause", "operational_guard").contains(authorization.reason()))
                return abortNeverSent(identity, authorization.reason());
            if ("already_authorized".equals(authorization.reason()))
                return new Dispatch(State.UNKNOWN, null);
            return new Dispatch(State.LOST, null);
        }
        boolean paused;
        try { paused = store.paused(); }
        catch (RuntimeException unavailable) { return new Dispatch(State.UNKNOWN, null); }
        Instant beforeSend = clock.instant();
        if (!inWindow(beforeSend) || paused || permit.cancelled.get())
            return abortNeverSent(identity, "window_or_pause");
        try { if (!dispatchAllowed.getAsBoolean()) return abortNeverSent(identity,"operational_guard"); }
        catch (RuntimeException unavailable) { return new Dispatch(State.UNKNOWN,null); }
        if (!beforeSend.isBefore(identity.leaseUntil()))
            return new Dispatch(State.LOST, null);
        try {
            return new Dispatch(State.SENT, sender.sendOnce(identity.frozenRequestJson()));
        } catch (RuntimeException sendUncertain) {
            return new Dispatch(State.UNKNOWN, null);
        }
    }

    private <T> Dispatch<T> abortNeverSent(Identity identity, String reason) {
        try {
            Boolean refunded = writes.execute(action(identity, "not-sent"),
                    store::committedAbort, context -> store.refundNotSent(identity,
                            "operational_guard".equals(reason) && inWindow(clock.instant())
                                    ? clock.instant().plusSeconds(60) : nextWindow(clock.instant()), reason, context.actionKey()));
            return new Dispatch(Boolean.TRUE.equals(refunded) ? State.NOT_SENT : State.LOST, null);
        } catch (RuntimeException uncertain) {
            return new Dispatch(State.UNKNOWN, null);
        }
    }
    private static Identity idleMarker() {
        return new Identity(null, null, null, 0, 0, Instant.EPOCH, null, null);
    }
    private static ActionContext action(Identity value, String step) {
        return new ActionContext(value.dispatchToken() + ":" + step,
                value.attemptId(), value.inputHash());
    }
    static boolean completeRequest(String json, String inputHash) {
        try {
            var request = CanonicalJson.parse(json);
            return request.isObject()
                    && "deepseek".equals(request.path("provider").asText())
                    && "deepseek-flash".equals(request.path("model").asText())
                    && request.path("messages").isArray()
                    && !request.path("messages").isEmpty()
                    && request.path("parameters").isObject()
                    && request.path("parameters").properties().stream().allMatch(field ->
                            Set.of("max_tokens", "thinking", "stop", "temperature", "top_p")
                                    .contains(field.getKey()))
                    && "json_object".equals(request.path("response_format").path("type").asText())
                    && request.path("schema_version").isTextual()
                    && request.path("prompt_version").isTextual()
                    && inputHash.equals(request.path("input_hash").asText())
                    && !secretField(request);
        } catch (RuntimeException invalid) { return false; }
    }
    private static boolean secretField(tools.jackson.databind.JsonNode node) {
        if (node.isObject()) for (var field : node.properties()) {
            if (Set.of("api_key", "authorization", "secret", "password")
                    .contains(field.getKey().toLowerCase()) || secretField(field.getValue()))
                return true;
        }
        if (node.isArray()) for (var value : node) if (secretField(value)) return true;
        return false;
    }
    static boolean inWindow(Instant instant) {
        LocalTime local = LocalDateTime.ofInstant(instant, SHANGHAI).toLocalTime();
        return !local.isBefore(LocalTime.of(1, 0)) && local.isBefore(LocalTime.of(7, 0));
    }
    static Instant nextWindow(Instant instant) {
        LocalDateTime local = LocalDateTime.ofInstant(instant, SHANGHAI);
        var day = local.toLocalDate();
        if (!local.toLocalTime().isBefore(LocalTime.of(7, 0))) day = day.plusDays(1);
        return day.atTime(1, 0).atZone(SHANGHAI).toInstant();
    }
}
