package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.jobs.SupplementGenerationService.ApplyResult;
import com.lianxibu.site.service.jobs.SupplementGenerationService.ApplyStatus;
import com.lianxibu.site.service.jobs.SupplementGenerationService.Command;
import com.lianxibu.site.service.jobs.SupplementGenerationService.Current;
import com.lianxibu.site.service.jobs.SupplementGenerationService.Entry;
import com.lianxibu.site.service.jobs.SupplementGenerationService.Member;
import com.lianxibu.site.service.jobs.SupplementGenerationService.Plan;
import com.lianxibu.site.service.jobs.SupplementGenerationService.Result;
import com.lianxibu.site.service.jobs.SupplementGenerationService.Run;
import com.lianxibu.site.service.jobs.SupplementGenerationService.RunKind;
import com.lianxibu.site.service.jobs.SupplementGenerationService.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SupplementGenerationServiceTest {
    private static final Instant ACCEPTED = Instant.parse("2026-09-30T08:00:00Z");
    private Fixture store;
    private SupplementGenerationService service;
    private JdbcTemplate jdbc;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:supplement-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE public_content(id VARCHAR(64) PRIMARY KEY,revision BIGINT,"
                + "state VARCHAR(16),body VARCHAR(128))");
        jdbc.update("INSERT INTO public_content VALUES('report',5,'published','old public body')");
        store = new Fixture();
        service = new SupplementGenerationService(new SharedWriteExecutor(
                new JdbcSharedWriteEntry(source), new DataSourceTransactionManager(source)),
                store, Clock.fixed(ACCEPTED, ZoneOffset.UTC));
    }

    @Test void noContentIdStillCreatesPeriodIntentAndNoChangeCannotResetBudget() {
        store.current = new Current("old-intent", 0, null, null, null, null);
        Result first = service.request(command(null, null), action("first", "intent-1"));
        assertNull(first.contentId());
        assertEquals(ACCEPTED, first.cutoff());
        assertEquals(1, first.generation());
        assertEquals(1, store.creates);
        assertEquals("published", publicState());
        assertThrows(SupplementGenerationService.Rejected.class,
                () -> service.request(command(null, null), action("same-input", "intent-2")));
        assertEquals(1, store.creates);
        assertEquals(1, service.request(command(null, null), action("first", "intent-1")).generation());
    }

    @Test void deltaInheritsOnlyProductionBaseAndExplicitExclusion() {
        store.runs = new ArrayList<>(List.of(
                run("base", 1, RunKind.FULL, null, "production", List.of(
                        include("a", "a"), include("b", "b"))),
                run("delta", 2, RunKind.DELTA, "base", "production", List.of(
                        exclude("a"), include("c", "c")))));
        var selected = SupplementGenerationService.select(store.runs, ACCEPTED);
        assertEquals(List.of("source\u0000window\u0000b", "source\u0000window\u0000c"),
                selected.entries().stream().map(Entry::key).toList());
        assertTrue(selected.missing().isEmpty());
        store.runs.set(0, run("base", 1, RunKind.FULL, null, "trial", List.of(include("a", "a"))));
        assertThrows(SupplementGenerationService.Rejected.class,
                () -> SupplementGenerationService.select(store.runs, ACCEPTED));
        store.runs.set(0, run("base", 1, RunKind.FULL, null, "production", List.of(include("a", "a"))));
        store.runs.set(1, run("delta", 2, RunKind.DELTA, "base", "production",
                List.of(new Member("a", null, null, false, null))));
        assertThrows(SupplementGenerationService.Rejected.class,
                () -> SupplementGenerationService.select(store.runs, ACCEPTED));
    }

    @Test void failurePreservesPublishedBodyAndLateSuccessCannotOverwriteNewerGeneration() {
        Result first = service.request(command("report", 5L), action("first", "intent-1"));
        assertEquals(ApplyStatus.FAILED, service.finish(first.intentId(), first.generation(),
                "report", 5L, false, action("fail", "completion")).status());
        assertEquals("old public body", publicBody());
        store.runs.add(run("new", 2, RunKind.FULL, null, "production",
                List.of(include("a", "changed"))));
        Result second = service.request(command("report", 5L), action("second", "intent-2"));
        assertEquals(2, second.generation());
        assertEquals(ApplyStatus.SUPERSEDED, service.finish(first.intentId(),
                first.generation(), "report", 5L, true, action("late", "completion")).status());
        assertEquals("published", publicState());
        assertEquals(ApplyStatus.APPLIED, service.finish(second.intentId(), second.generation(),
                "report", 5L, true, action("complete", "completion")).status());
        assertEquals("draft", publicState());
        assertEquals("new generated body", publicBody());
    }

    @Test void contentRevisionConflictKeepsOriginalAndTrialRunCannotBecomeInput() {
        store.runs = new ArrayList<>(List.of(run("trial", 1, RunKind.FULL, null, "trial",
                List.of(include("x", "x")))));
        Result intent = service.request(command("report", 5L), action("trial", "intent-trial"));
        assertEquals("partial", intent.coverage());
        assertEquals(List.of(), store.lastPlan.entries());
        jdbc.update("UPDATE public_content SET revision=revision+1 WHERE id='report'");
        assertEquals(ApplyStatus.CONTENT_REVISION_CONFLICT, service.finish(intent.intentId(),
                intent.generation(), "report", 5L, true, action("conflict", "completion")).status());
        assertEquals("old public body", publicBody());
    }

    @Test void resolvedMissingWindowIsSubstantiveEvenWithoutSelectedMembers() {
        store.runs = new ArrayList<>(List.of(new Run("incomplete", "source", "window", 1,
                RunKind.FULL, null, "production", "running", null, List.of())));
        Result first = service.request(command("report", 5L), action("missing", "intent-missing"));
        assertEquals("partial", first.coverage());
        store.runs.add(run("empty-complete", 2, RunKind.FULL, null, "production", List.of()));
        Result second = service.request(command("report", 5L), action("resolved", "intent-resolved"));
        assertEquals("complete", second.coverage());
        assertNotEquals(first.businessInputHash(), second.businessInputHash());
    }

    @Test void completionReceiptPreventsSecondBodyWrite() {
        Result requested = service.request(command("report", 5L), action("request", "intent"));
        ApplyResult applied = service.finish(requested.intentId(), requested.generation(),
                "report", 5L, true, action("finish", "completion"));
        assertEquals(ApplyStatus.APPLIED, applied.status());
        assertEquals(applied, service.finish(requested.intentId(), requested.generation(),
                "report", 5L, true, action("finish", "completion")));
        assertEquals(6L, jdbc.queryForObject("SELECT revision FROM public_content", Long.class));
        assertThrows(IllegalStateException.class, () -> service.finish(requested.intentId(),
                requested.generation(), "report", 5L, false, action("finish", "completion")));
    }

    private String publicState() { return jdbc.queryForObject("SELECT state FROM public_content", String.class); }
    private String publicBody() { return jdbc.queryForObject("SELECT body FROM public_content", String.class); }
    private static ActionContext action(String key, String resource) { return new ActionContext(key, resource, null); }
    private static Command command(String content, Long revision) {
        return new Command("daily", Instant.parse("2026-09-28T16:00:00Z"),
                Instant.parse("2026-09-29T16:00:00Z"), "Asia/Shanghai", content, revision,
                "supplement fixture");
    }
    private static Run run(String id, long generation, RunKind kind, String base,
                           String activation, List<Member> members) {
        return new Run(id, "source", "window", generation, kind, base, activation,
                "complete", ACCEPTED.minusSeconds(60), members);
    }
    private static Member include(String key, String material) {
        return new Member(key, material.equals("changed") ? "d".repeat(64) :
                material.equals("b") ? "b".repeat(64) : "a".repeat(64),
                "{\"selected\":true}", true, null);
    }
    private static Member exclude(String key) {
        return new Member(key, null, null, false, "explicit exclusion");
    }

    private final class Fixture implements Store {
        Current current = new Current("old-intent", 0, "report", 5L, null, "published");
        List<Run> runs = new ArrayList<>(List.of(run("base", 1, RunKind.FULL, null,
                "production", List.of(include("a", "a")))));
        final Map<String, Result> receipts = new HashMap<>();
        final Map<String, String> hashes = new HashMap<>();
        final Map<String, ApplyResult> completions = new HashMap<>();
        final Map<String, String> completionHashes = new HashMap<>();
        Plan lastPlan;
        int creates;
        @Override public Result committed(String key, String hash) {
            if (hashes.containsKey(key) && !hash.equals(hashes.get(key)))
                throw new IllegalStateException("action conflict");
            return receipts.get(key);
        }
        @Override public Current current(Command command) { return current; }
        @Override public List<Run> runs(Command command, Instant cutoff) { return runs; }
        @Override public List<Entry> upstream(Command command, Instant cutoff) { return List.of(); }
        @Override public String settingsJson(Command command) { return "{\"blocked\":[],\"follow\":[]}"; }
        @Override public String taskDefinitionVersion(String type) { return "report-v1"; }
        @Override public void create(Plan plan, String key, String hash) {
            lastPlan = plan;
            creates++;
            current = new Current(plan.intentId(), plan.generation(), plan.contentId(),
                    plan.contentRevision(), plan.businessInputHash(), "published");
            receipts.put(key, new Result(plan.intentId(), plan.generation(), plan.contentId(),
                    plan.cutoff(), plan.coverage(), plan.businessInputHash(), false));
            hashes.put(key, hash);
        }
        @Override public ApplyResult completed(String key, String hash) {
            if (completionHashes.containsKey(key) && !hash.equals(completionHashes.get(key)))
                throw new IllegalStateException("action conflict");
            return completions.get(key);
        }
        @Override public ApplyResult finish(String intent, long generation, String content,
                                            Long expected, boolean succeeded, String key,
                                            String hash) {
            ApplyResult result;
            if (!succeeded) result = new ApplyResult(ApplyStatus.FAILED, content, expected);
            else if (current.generation() != generation || !current.intentId().equals(intent))
                result = new ApplyResult(ApplyStatus.SUPERSEDED, content, expected);
            else {
                int changed = jdbc.update("UPDATE public_content SET state='draft',body='new generated body',"
                        + "revision=revision+1 WHERE id=? AND revision=?", content, expected);
                result = changed != 1
                        ? new ApplyResult(ApplyStatus.CONTENT_REVISION_CONFLICT, content, expected)
                        : new ApplyResult(ApplyStatus.APPLIED, content, expected + 1);
            }
            completions.put(key, result);
            completionHashes.put(key, hash);
            return result;
        }
    }
}
