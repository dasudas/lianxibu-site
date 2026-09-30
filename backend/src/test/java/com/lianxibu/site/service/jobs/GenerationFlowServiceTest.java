package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.GenerationFlowService.Dependency;
import com.lianxibu.site.service.jobs.GenerationFlowService.Evidence;
import com.lianxibu.site.service.jobs.GenerationFlowService.Frozen;
import com.lianxibu.site.service.jobs.GenerationFlowService.Prepare;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class GenerationFlowServiceTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final MutableClock time = new MutableClock("2026-10-01T04:29:00+08:00");
    private JdbcTemplate jdbc;
    private GenerationFlowService service;
    private boolean invalidRequest;
    private boolean preparationAllowed = true;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:freeze-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE frozen(action_key VARCHAR(128) PRIMARY KEY,owner_id VARCHAR(64),"
                + "logical_key VARCHAR(128),generation BIGINT,stage VARCHAR(32),cutoff TIMESTAMP,"
                + "coverage VARCHAR(16),missing_json CLOB,snapshot_json CLOB,input_hash VARCHAR(64),"
                + "request_json CLOB,priority INT,eligible_at TIMESTAMP,state VARCHAR(32))");
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                new DataSourceTransactionManager(source), () -> 25, ignored -> { });
        GenerationFlowService.FrozenStore store = new GenerationFlowService.FrozenStore() {
            @Override public Frozen committed(String actionKey) {
                var rows = jdbc.query("SELECT owner_id,logical_key,generation,stage,cutoff,coverage,"
                                + "missing_json,snapshot_json,input_hash,request_json,priority,eligible_at,state"
                                + " FROM frozen WHERE action_key=?", (rs, ignored) -> {
                            List<String> missing = new ArrayList<>();
                            for (var node : CanonicalJson.parse(rs.getString(7))) missing.add(node.asText());
                            return new Frozen(rs.getString(1), rs.getString(2), rs.getLong(3),
                                    Stage.valueOf(rs.getString(4)), rs.getTimestamp(5).toInstant(),
                                    rs.getString(6), List.copyOf(missing), rs.getString(8), rs.getString(9),
                                    rs.getString(10), rs.getInt(11), rs.getTimestamp(12).toInstant(),
                                    rs.getString(13));
                        }, actionKey);
                return rows.isEmpty() ? null : rows.getFirst();
            }
            @Override public void save(Frozen value, String actionKey) {
                jdbc.update("INSERT INTO frozen VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", actionKey,
                        value.ownerId(), value.logicalKey(), value.generation(), value.stage().name(),
                        Timestamp.from(value.cutoff()), value.coverageState(),
                        JsonMapper.shared().writeValueAsString(value.missing()), value.snapshotJson(),
                        value.inputHash(), value.requestJson(), value.priority(),
                        Timestamp.from(value.eligibleAt()), value.state());
            }
        };
        service = new GenerationFlowService(writes, store, (stage, snapshot, hash) -> invalidRequest
                ? "{\"model\":\"deepseek-flash\"}"
                : "{\"provider\":\"deepseek\",\"model\":\"deepseek-flash\","
                + "\"input_hash\":\"" + hash + "\",\"messages\":[{\"role\":\"user\","
                + "\"content\":\"fixture\"}],\"response_format\":{\"type\":\"json_object\"},"
                + "\"parameters\":{},\"schema_version\":\"fixture-v1\","
                + "\"prompt_version\":\"fixture-v1\"}", time, () -> preparationAllowed);
    }

    @Test void capacityStopsNewPreparationWithoutDeletingFrozenInputsOrBlockingReplay() {
        Prepare value=command(Stage.AD_HOC_AI,LocalDate.of(2026,9,30),List.of(),
                List.of(evidence("fictional-input",LocalDate.of(2026,9,30),"2026-10-01T04:00:00+08:00")),List.of());
        Frozen frozen=service.prepare(value,action("before-guard"));
        preparationAllowed=false;
        assertEquals(frozen,service.prepare(value,action("before-guard")));
        assertThrows(GenerationFlowService.CapacityGuarded.class,()->service.prepare(value,action("new-guarded")));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM frozen",Long.class));
        preparationAllowed=true;
        assertNotEquals(frozen.ownerId(),service.prepare(value,action("new-guarded")).ownerId());
    }

    @Test void prioritiesCoverFourteenStagesAndBarrierClosesAtFourThirty() {
        for (int i = 0; i < Stage.values().length; i++) assertEquals(i + 1, Stage.values()[i].priority());
        Prepare newsValue = command(Stage.NEWS_VALUE, LocalDate.of(2026, 9, 30),
                List.of(new Dependency("p1-run", Stage.NEWS_DEDUP, "running", null)),
                List.of(evidence("article-a", LocalDate.of(2026, 9, 30), "2026-10-01T04:00:00+08:00")),
                List.of("p1-run"));
        assertEquals("waiting_input", service.prepare(newsValue, action("before-barrier")).state());
        time.set("2026-10-01T04:30:00+08:00");
        Frozen partial = service.prepare(newsValue, action("at-barrier"));
        assertEquals("queued", partial.state());
        assertEquals("partial", partial.coverageState());
        assertEquals(List.of("p1-run"), partial.missing());
        assertNotNull(partial.inputHash());
        assertEquals("queued", service.prepare(newsValue, action("at-barrier")).state());
        time.set("2026-10-01T04:50:00+08:00");
        Prepare lateCompletion = command(Stage.NEWS_VALUE, LocalDate.of(2026, 9, 30),
                List.of(new Dependency("p1-run", Stage.NEWS_DEDUP, "succeeded",
                        java.time.OffsetDateTime.parse("2026-10-01T04:40:00+08:00").toInstant())),
                List.of(evidence("late-p1", LocalDate.of(2026, 9, 30),
                        "2026-10-01T04:40:00+08:00")), List.of("p1-run"));
        assertEquals("no_updates", service.prepare(lateCompletion, action("late-p1")).state());
        assertEquals("partial", service.prepare(lateCompletion, action("late-p1")).coverageState());
    }

    @Test void eachStageRetainsPriorityAndDependencyReadiness() {
        LocalDate day = LocalDate.of(2026, 9, 30);
        for (Stage stage : Stage.values()) {
            if (stage.report()) continue;
            Stage upstream = switch (stage) {
                case NEWS_VALUE -> Stage.NEWS_DEDUP;
                case AGENT_ANALYSIS -> Stage.AGENT_DEDUP;
                case SKILL_ANALYSIS -> Stage.SKILL_DEDUP;
                case WORKFLOW_ANALYSIS -> Stage.WORKFLOW_DEDUP;
                case MODEL_ANALYSIS -> Stage.MODEL_DEDUP;
                default -> Stage.NEWS_DEDUP;
            };
            Prepare pending = command(stage, day,
                    List.of(new Dependency(stage.name() + "-input", upstream, "running", null)),
                    List.of(evidence(stage.name() + "-item", day,
                            "2026-10-01T04:00:00+08:00")),
                    List.of(stage.name() + "-input"));
            String key = "priority-" + stage.priority();
            if (stage.dedup() || stage == Stage.NEWS_VALUE || stage.analysis()) {
                assertEquals("waiting_input", service.prepare(pending, action(key)).state(),
                        stage.name());
            } else {
                assertEquals("queued", service.prepare(pending, action(key)).state(),
                        stage.name());
            }
        }
        time.set("2026-10-01T04:00:00+08:00");
        Prepare early = command(Stage.SKILL_ANALYSIS, day,
                List.of(new Dependency("first", Stage.SKILL_DEDUP, "succeeded",
                        java.time.OffsetDateTime.parse("2026-10-01T03:59:00+08:00").toInstant())),
                List.of(evidence("skill", day, "2026-10-01T03:59:00+08:00")),
                List.of("first", "second"));
        assertEquals("waiting_input", service.prepare(early, action("missing-early")).state());
    }

    @Test void reportOriginalCutoffIsFiveAndLateOrBlockedInputCannotReenter() {
        Prepare daily = command(Stage.DAILY_REPORT, LocalDate.of(2026, 9, 30),
                List.of(new Dependency("source-a", Stage.NEWS_VALUE, "succeeded",
                        Instant.parse("2026-09-30T20:45:00Z")),
                        new Dependency("source-b", Stage.NEWS_VALUE, "running", null)),
                List.of(evidence("good", LocalDate.of(2026, 9, 30), "2026-10-01T04:40:00+08:00"),
                        evidence("late", LocalDate.of(2026, 9, 30), "2026-10-01T05:01:00+08:00"),
                        blocked("blocked", LocalDate.of(2026, 9, 30)),
                        referenced("summary-blocked", "blocked", LocalDate.of(2026, 9, 30)),
                        referenced("summary-chain", "summary-blocked", LocalDate.of(2026, 9, 30))),
                List.of("source-a", "source-b"));
        assertEquals("waiting_input", service.prepare(daily, action("daily-early")).state());
        time.set("2026-10-01T05:00:00+08:00");
        Frozen frozen = service.prepare(daily, action("daily-cutoff"));
        assertEquals("partial", frozen.coverageState());
        assertEquals(List.of("blocked", "late", "source-b", "summary-blocked",
                "summary-chain"), frozen.missing());
        assertEquals(1, CanonicalJson.parse(frozen.snapshotJson()).get("members").size());
        assertEquals("good", CanonicalJson.parse(frozen.snapshotJson()).get("members").get(0)
                .get("id").asText());
        String originalHash = frozen.inputHash();
        time.set("2026-10-02T05:00:00+08:00");
        assertEquals(originalHash, service.prepare(daily, action("daily-cutoff")).inputHash());
    }

    @Test void weeklyAndMonthlyFreezeOnlyActualPriorPeriodDays() {
        time.set("2026-10-05T05:00:00+08:00");
        LocalDate sunday = LocalDate.of(2026, 10, 4);
        Prepare week = command(Stage.WEEKLY_REPORT, sunday, List.of(),
                List.of(evidence("monday", LocalDate.of(2026, 9, 28), "2026-10-04T00:00:00+08:00"),
                        evidence("sunday", sunday, "2026-10-05T04:59:00+08:00"),
                        evidence("outside", LocalDate.of(2026, 9, 27), "2026-10-04T00:00:00+08:00")),
                List.of());
        Frozen weekly = service.prepare(week, action("week"));
        assertEquals(2, CanonicalJson.parse(weekly.snapshotJson()).get("members").size());
        assertEquals(5, weekly.missing().stream().filter(id -> id.startsWith("date:")).count());
        time.set("2026-10-01T05:00:00+08:00");
        LocalDate september = LocalDate.of(2026, 9, 30);
        Prepare month = command(Stage.MONTHLY_REPORT, september, List.of(),
                List.of(evidence("first", LocalDate.of(2026, 9, 1), "2026-09-30T12:00:00+08:00"),
                        evidence("last", september, "2026-10-01T04:59:00+08:00"),
                        evidence("august", LocalDate.of(2026, 8, 31), "2026-09-30T12:00:00+08:00")),
                List.of());
        Frozen monthly = service.prepare(month, action("month"));
        assertEquals(2, CanonicalJson.parse(monthly.snapshotJson()).get("members").size());
        assertEquals(28, monthly.missing().stream().filter(id -> id.startsWith("date:")).count());
    }

    @Test void confirmedNoUpdatesIsCoverageOnlyWithoutModelRequest() {
        time.set("2026-10-05T05:00:00+08:00");
        LocalDate sunday = LocalDate.of(2026, 10, 4);
        Evidence noUpdates = new Evidence("day-no-updates", "no_updates", sunday,
                java.time.OffsetDateTime.parse("2026-10-05T04:00:00+08:00").toInstant(),
                "a".repeat(64), "{\"state\":\"no_updates\"}", true, false, null, List.of());
        Prepare week = command(Stage.WEEKLY_REPORT, sunday, List.of(),
                List.of(noUpdates), List.of());
        invalidRequest = true;
        Frozen result = service.prepare(week, action("coverage-only"));
        assertEquals("coverage_only", result.state());
        assertNotNull(result.inputHash());
        assertNull(result.requestJson());
        assertEquals(6, result.missing().stream().filter(id -> id.startsWith("date:")).count());
    }

    @Test void emptyReportNeverCallsModelAndIncompleteRequestRollsBackFreeze() {
        time.set("2026-10-01T05:00:00+08:00");
        Prepare empty = command(Stage.DAILY_REPORT, LocalDate.of(2026, 9, 30),
                List.of(), List.of(), List.of("missing-source"));
        assertEquals("no_input", service.prepare(empty, action("empty")).state());
        assertNull(service.prepare(empty, action("empty")).requestJson());
        invalidRequest = true;
        Prepare nonempty = command(Stage.DAILY_REPORT, LocalDate.of(2026, 9, 30), List.of(),
                List.of(evidence("good", LocalDate.of(2026, 9, 30), "2026-10-01T04:00:00+08:00")),
                List.of());
        assertThrows(GenerationFlowService.InputRejected.class,
                () -> service.prepare(nonempty, action("bad-request")));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM frozen", Long.class));
    }

    private static Prepare command(Stage stage, LocalDate date, List<Dependency> dependencies,
                                   List<Evidence> evidence, List<String> expected) {
        return new Prepare(stage.name().toLowerCase() + ":" + date, 1, stage, date,
                "source-a", dependencies, evidence, expected, false, "a".repeat(64));
    }
    private static Evidence evidence(String id, LocalDate date, String finished) {
        return new Evidence(id, "article", date, Instant.parse(
                java.time.OffsetDateTime.parse(finished).toInstant().toString()),
                "a".repeat(64), "{\"title\":\"fixture\"}", true, false, null, List.of());
    }
    private static Evidence blocked(String id, LocalDate date) {
        return new Evidence(id, "article", date, Instant.parse("2026-09-30T20:00:00Z"),
                "b".repeat(64), "{\"title\":\"blocked\"}", false, true, null, List.of());
    }
    private static Evidence referenced(String id, String ref, LocalDate date) {
        Evidence base = evidence(id, date, "2026-10-01T04:40:00+08:00");
        return new Evidence(base.id(), base.kind(), base.businessDate(), base.completedAt(),
                base.bodyHash(), base.bodyJson(), true, false, null, List.of(ref));
    }
    private static ActionContext action(String key) { return new ActionContext(key, "owner-" + key, null); }
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(String time) { set(time); }
        void set(String time) { now = java.time.OffsetDateTime.parse(time).toInstant(); }
        @Override public ZoneId getZone() { return SHANGHAI; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
