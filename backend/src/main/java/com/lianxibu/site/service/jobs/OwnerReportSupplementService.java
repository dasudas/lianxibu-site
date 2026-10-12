package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Two frozen owner supplement endpoints. Existing actions are resolved before current target metadata. */
public final class OwnerReportSupplementService {
    private final ContentReadService contents;
    private final JdbcLatestReportIntent intents;
    private final JdbcReportSupplementActions actions;
    private final ReportSupplementPreparationService preparation;
    private final TransactionTemplate reads;

    public OwnerReportSupplementService(PlatformTransactionManager manager, ContentReadService contents,
            JdbcLatestReportIntent intents, JdbcReportSupplementActions actions,
            ReportSupplementPreparationService preparation) {
        this.contents = Objects.requireNonNull(contents);
        this.intents = Objects.requireNonNull(intents);
        this.actions = Objects.requireNonNull(actions);
        this.preparation = Objects.requireNonNull(preparation);
        reads = new TransactionTemplate(Objects.requireNonNull(manager));
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(1);
    }

    public ContentService.ActionResult action(Actor actor, String kind, String id,
            ContentService.ContentAction request, String key, String csrf, String origin) {
        owner(actor); headers(key, csrf, origin); WireValues.id(id, false);
        if (!"report".equals(kind) || request == null || !"supplement_generate".equals(request.action())
                || request.expectedRevision() < 1 || request.expectedRevision() >= CanonicalJson.MAX_SAFE_INTEGER
                || request.withdrawalReason() != null) throw new IllegalArgumentException("invalid report supplement");
        reason(request.reason());
        return execute(() -> {
            var command = reads.execute(status -> {
                var saved = actions.original(JdbcReportSupplementActions.action(key));
                if (saved != null) {
                    var old = saved.command();
                    if (!actor.userId().equals(old.actor()) || !request.reason().equals(old.reason())
                            || !id.equals(old.expectedContentId())
                            || !Long.valueOf(request.expectedRevision()).equals(old.expectedContentRevision()))
                        throw new JdbcReportSupplementActions.Conflict();
                    return old;
                }
                var value = contents.comparisonVersion("report", id).orElseThrow(OwnerReportSupplementService::missing);
                if (!value.proven()) throw unavailable();
                if (value.revision() != request.expectedRevision()) throw revision();
                var body = FrozenContentBody.read("report", CanonicalJson.parse(value.bodyJson()));
                if (!(body instanceof ContentService.ReportBody report)) throw unavailable();
                var scope = scope(report.type(), report.periodStart(), report.periodEnd(), report.timezone());
                if (!JdbcFrozenInputs.stableId("report-content", scope.logicalKey()).equals(id)) throw unavailable();
                var latest = intents.read(scope); if (latest == null) throw unavailable();
                return new JdbcReportSupplementActions.Command(scope, latest.revision(), id,
                        request.expectedRevision(), actor.userId(), request.reason());
            });
            return ReportSupplementResponse.action(preparation.prepare(actor, command, key));
        });
    }

    public ContentService.Job generationIntent(Actor actor, ContentService.GenerationIntentWrite request,
            String key, String csrf, String origin) {
        owner(actor); headers(key, csrf, origin);
        if (request == null || !Set.of("initial", "supplement").contains(request.mode()))
            throw new IllegalArgumentException("invalid report generation mode");
        reason(request.reason());
        var scope = scope(request.kind(), request.periodStart(), request.periodEnd(), request.timezone());
        if (!"supplement".equals(request.mode())) throw unavailable();
        if (request.intentRevision() == null) throw new IllegalArgumentException("intent revision required");
        var command = new JdbcReportSupplementActions.Command(scope, request.intentRevision(), null, null,
                actor.userId(), request.reason());
        return execute(() -> ReportSupplementResponse.job(preparation.prepare(actor, command, key)));
    }

    private static JdbcLatestReportIntent.Scope scope(String type, Instant start, Instant end, String timezone) {
        if (start == null || end == null || type == null || !"Asia/Shanghai".equals(timezone)
                || !Set.of("daily", "weekly", "monthly").contains(type))
            throw new IllegalArgumentException("invalid natural report period");
        ZoneId zone = ZoneId.of("Asia/Shanghai"); LocalDate day = end.atZone(zone).toLocalDate().minusDays(1);
        LocalDate first = switch (type) { case "daily" -> day; case "weekly" -> day.minusDays(6);
            case "monthly" -> day.withDayOfMonth(1); default -> throw new IllegalArgumentException("invalid report type"); };
        if (!first.atStartOfDay(zone).toInstant().equals(start) || !day.plusDays(1).atStartOfDay(zone).toInstant().equals(end))
            throw new IllegalArgumentException("invalid natural report period");
        return new JdbcLatestReportIntent.Scope("report:" + type + ":" + day, type + "_report", day);
    }

    private static <T> T execute(Supplier<T> work) {
        try { return work.get(); }
        catch (IdentityService.Rejected error) { throw error; }
        catch (JdbcReportSupplementActions.Conflict error) {
            throw new IdentityService.Rejected(IdentityService.Refusal.IDENTITY_CONFLICT, null);
        } catch (ReportSupplementBaselineService.Conflict error) { throw revision(); }
        catch (ReportSupplementPreparationService.Rejected error) {
            throw new IdentityService.Rejected(IdentityService.Refusal.REVISION_CONFLICT, null, error.getMessage());
        } catch (RuntimeException error) { throw unavailable(); }
    }
    private static void owner(Actor actor) {
        if (actor == null || actor.userId() == null || actor.userId().isBlank())
            throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED, null);
        if (!"owner".equals(actor.role())) throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN, null);
    }
    private static void headers(String key, String csrf, String origin) {
        JdbcReportSupplementActions.action(key);
        if (csrf == null || csrf.isBlank() || origin == null || origin.isBlank())
            throw new IllegalArgumentException("protected owner headers required");
    }
    private static void reason(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 2048)
            throw new IllegalArgumentException("supplement reason required");
    }
    private static IdentityService.Rejected revision() { return new IdentityService.Rejected(IdentityService.Refusal.REVISION_CONFLICT, null); }
    private static IdentityService.Rejected missing() { return new IdentityService.Rejected(IdentityService.Refusal.NOT_FOUND, null); }
    private static IdentityService.Rejected unavailable() { return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE, "1"); }
    @Override public String toString() { return "OwnerReportSupplementService[redacted]"; }
}
