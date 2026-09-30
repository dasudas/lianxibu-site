package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import com.lianxibu.site.service.jobs.ResultApplicationService.Applied;
import com.lianxibu.site.service.jobs.ResultApplicationService.Current;
import com.lianxibu.site.service.jobs.ResultApplicationService.Input;
import com.lianxibu.site.service.jobs.ResultApplicationService.Result;
import com.lianxibu.site.service.jobs.ResultApplicationService.Status;
import com.lianxibu.site.service.jobs.ResultApplicationService.Store;
import com.lianxibu.site.service.jobs.ResultApplicationService.Validated;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ResultApplicationServiceTest {
    private static final String INPUT_HASH = "a".repeat(64);
    private JdbcTemplate jdbc;
    private ResultApplicationService service;
    private boolean failDownstream;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:result-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE task(root_id VARCHAR(64) PRIMARY KEY,generation BIGINT,"
                + "input_hash VARCHAR(64),root_revision BIGINT,content_revision BIGINT,"
                + "dependencies_valid BOOLEAN,discarded BOOLEAN,applied_hash VARCHAR(64),"
                + "retained_kind VARCHAR(20),retained_payload CLOB,retained_hash VARCHAR(64),"
                + "application_state VARCHAR(40),active_attempt_id VARCHAR(64),"
                + "ownership_valid BOOLEAN)");
        jdbc.update("INSERT INTO task VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", "root", 1L,
                INPUT_HASH, 0L, 4L, true, false, null, null, null, null, null,
                "attempt", true);
        jdbc.execute("CREATE TABLE content(id VARCHAR(64) PRIMARY KEY,body_revision BIGINT,"
                + "publication_state VARCHAR(20),review_hash VARCHAR(64))");
        jdbc.update("INSERT INTO content VALUES('content',4,'published','review')");
        jdbc.execute("CREATE TABLE downstream(id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE result_receipt(action_key VARCHAR(128) PRIMARY KEY,"
                + "action_hash VARCHAR(64),"
                + "status VARCHAR(40),result_hash VARCHAR(64),content_id VARCHAR(64),"
                + "content_revision BIGINT)");
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                new DataSourceTransactionManager(source), () -> 25, ignored -> { });
        Store store = new Store() {
            @Override public Result committed(String key, String hash) {
                var rows = jdbc.query("SELECT action_hash,status,result_hash,content_id,"
                        + "content_revision FROM result_receipt WHERE action_key=?",
                        (rs, ignored) -> new Object[]{rs.getString(1),
                                new Result(Status.valueOf(rs.getString(2)), rs.getString(3),
                                        rs.getString(4), rs.getObject(5) == null ? null
                                                : rs.getLong(5))}, key);
                if (rows.isEmpty()) return null;
                if (!hash.equals(rows.getFirst()[0])) throw new IllegalStateException("action conflict");
                return (Result) rows.getFirst()[1];
            }
            @Override public Current current(String rootId) {
                return jdbc.queryForObject("SELECT generation,input_hash,root_revision,"
                        + "content_revision,dependencies_valid,discarded,applied_hash,retained_hash,"
                        + "active_attempt_id,ownership_valid,application_state"
                        + " FROM task WHERE root_id=?", (rs, ignored) ->
                        new Current(rs.getLong(1), rs.getString(2), rs.getLong(3),
                                rs.getObject(4) == null ? null : rs.getLong(4),
                                rs.getBoolean(5), rs.getBoolean(6), rs.getString(7),
                                rs.getString(8), rs.getString(9), rs.getBoolean(10),
                                rs.getString(11)), rootId);
            }
            @Override public void retain(String rootId, String attemptId, Validated result) {
                String old = jdbc.queryForObject("SELECT retained_hash FROM task WHERE root_id=?",
                        String.class, rootId);
                if (old != null && !old.equals(result.resultHash()))
                    throw new IllegalStateException("result changed");
                jdbc.update("UPDATE task SET retained_kind=?,retained_payload=?,retained_hash=?"
                        + " WHERE root_id=?", result.kind(), result.payloadJson(),
                        result.resultHash(), rootId);
            }
            @Override public void defer(String rootId, Status reason, String hash,
                                        String actionKey, String actionHash) {
                jdbc.update("UPDATE task SET application_state=? WHERE root_id=?",
                        reason.name(), rootId);
                jdbc.update("INSERT INTO result_receipt VALUES(?,?,?,?,?,?)", actionKey,
                        actionHash, reason.name(), hash, null, null);
            }
            @Override public void markApplied(String rootId, String hash,
                                             String contentId, Long revision,
                                             String actionKey, String actionHash) {
                int changed = jdbc.update("UPDATE task SET applied_hash=?,"
                        + "application_state='APPLIED',content_revision=?,root_revision=root_revision+1"
                        + " WHERE root_id=? AND applied_hash IS NULL", hash, revision, rootId);
                if (changed != 1) throw new IllegalStateException("already applied");
                jdbc.update("INSERT INTO result_receipt VALUES(?,?,?,?,?,?)", actionKey,
                        actionHash, Status.APPLIED.name(), hash, contentId, revision);
            }
            @Override public Validated retained(String rootId) {
                var rows = jdbc.query("SELECT retained_kind,retained_payload,retained_hash,"
                        + "input_hash FROM task WHERE root_id=? AND retained_hash IS NOT NULL",
                        (rs, ignored) -> new Validated(rs.getString(1), rs.getString(2),
                                rs.getString(3), rs.getString(4)), rootId);
                return rows.isEmpty() ? null : rows.getFirst();
            }
        };
        ResultApplicationService.Handler handler = (input, result, current, manual) -> {
            int changed = jdbc.update("UPDATE content SET body_revision=body_revision+1,"
                    + "publication_state='draft',review_hash=NULL WHERE id='content'"
                    + " AND body_revision=?", current.contentRevision());
            if (changed != 1) return null;
            jdbc.update("INSERT INTO downstream VALUES(?)", input.rootId() + ":next");
            if (failDownstream) throw new IllegalStateException("downstream failed");
            return new Applied("content", current.contentRevision() + 1);
        };
        service = new ResultApplicationService(writes, store, Map.of(
                "dedup", handler, "value", handler, "analysis", handler,
                "report", handler, "ad_hoc", handler));
    }

    @Test void allFiveBranchesValidateAndRejectWrongInputOrReferences() {
        Input dedup = input(Stage.AGENT_DEDUP);
        String dedupPayload = "{\"kind\":\"dedup\",\"items\":[{\"item_id\":\"item-1\","
                + "\"category\":\"agent\",\"decision\":\"existing\","
                + "\"candidate_entity_id\":\"entity-1\",\"reason\":\"same\","
                + "\"confidence\":\"0.9\",\"suggested_title\":\"Agent\","
                + "\"suggested_summary\":null,\"reanalyze_recommended\":false}],"
                + "\"summary\":\"one match\"}";
        assertEquals("dedup", ResultApplicationService.validate(dedup,
                raw(dedupPayload)).kind());
        assertThrows(ResultApplicationService.Rejected.class,
                () -> ResultApplicationService.validate(dedup,
                        raw(dedupPayload.replace("entity-1", "outside-candidate"))));
        String value = "{\"kind\":\"value\",\"items\":[{\"item_id\":\"item-1\","
                + "\"category\":\"news_tech\",\"high_value\":true,"
                + "\"reason\":\"useful\"}],\"selected_item_ids\":[\"item-1\"],"
                + "\"summary\":\"one selected\"}";
        assertEquals("value", ResultApplicationService.validate(input(Stage.NEWS_VALUE),
                raw(value)).kind());
        assertThrows(ResultApplicationService.Rejected.class,
                () -> ResultApplicationService.validate(input(Stage.NEWS_VALUE),
                        raw(value.replace("\"item-1\"],", "\"outside\"],"))));
        String analysis = "{\"kind\":\"analysis\",\"body\":{\"title\":\"Review\","
                + "\"sections\":[],\"citation_ids\":[\"cite-1\"],"
                + "\"category\":\"agent\",\"entity_id\":\"entity-1\"}}";
        assertEquals("analysis", ResultApplicationService.validate(input(Stage.AGENT_ANALYSIS),
                raw(analysis)).kind());
        assertThrows(ResultApplicationService.Rejected.class,
                () -> ResultApplicationService.validate(input(Stage.AGENT_ANALYSIS),
                        raw(analysis.replace("cite-1", "blocked-citation"))));
        String report = reportPayload();
        assertEquals("report", ResultApplicationService.validate(input(Stage.DAILY_REPORT),
                raw(report)).kind());
        assertThrows(ResultApplicationService.Rejected.class,
                () -> ResultApplicationService.validate(input(Stage.WEEKLY_REPORT), raw(report)));
        String adhoc = "{\"kind\":\"ad_hoc\",\"title\":\"Answer\","
                + "\"sections\":[],\"citation_ids\":[]}";
        assertEquals("ad_hoc", ResultApplicationService.validate(input(Stage.AD_HOC_AI),
                raw(adhoc)).kind());
        assertThrows(ResultApplicationService.Rejected.class,
                () -> ResultApplicationService.validate(input(Stage.AD_HOC_AI),
                        raw(adhoc.replace("\"sections\":[]",
                                "\"sections\":[],\"unexpected\":true"))));
        String signed = raw(report);
        assertThrows(ResultApplicationService.Rejected.class,
                () -> ResultApplicationService.validate(input(Stage.DAILY_REPORT),
                        signed.replace("\"Daily\"", "\"Edited\"")));
        assertThrows(ResultApplicationService.Rejected.class,
                () -> ResultApplicationService.validate(input(Stage.DAILY_REPORT),
                        signed.replace(INPUT_HASH, "b".repeat(64))));
        Input regular = input(Stage.DAILY_REPORT);
        Input malformedPeriod = new Input(regular.rootId(), regular.attemptId(),
                regular.stage(), regular.generation(), regular.inputHash(),
                regular.expectedRootRevision(), regular.expectedContentRevision(),
                regular.itemIds(), regular.candidatesByItem(),
                regular.eligibleHighValueItems(), regular.allowedCitationIds(),
                regular.versionEligibleItems(), regular.targetEntityId(),
                "2026-09-29T16:00:00Z", regular.periodEnd());
        assertThrows(ResultApplicationService.Rejected.class,
                () -> ResultApplicationService.validate(malformedPeriod,
                        raw(report.replace("2026-09-29T16:00:00.000000Z",
                                "2026-09-29T16:00:00Z"))));
    }

    @Test void successfulReportReplacesBodyToDraftExactlyOnceAndReplayNeedsNoAi() {
        String raw = raw(reportPayload());
        Result first = service.applyAutomatic(input(Stage.DAILY_REPORT), raw, action("apply"));
        assertEquals(Status.APPLIED, first.status());
        assertEquals("draft", jdbc.queryForObject(
                "SELECT publication_state FROM content", String.class));
        assertNull(jdbc.queryForObject("SELECT review_hash FROM content", String.class));
        assertEquals(5L, jdbc.queryForObject("SELECT body_revision FROM content", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM downstream", Integer.class));
        assertEquals(first, service.applyAutomatic(input(Stage.DAILY_REPORT), raw, action("apply")));
        assertThrows(IllegalStateException.class, () -> service.applyAutomatic(
                input(Stage.DAILY_REPORT), raw(reportPayload().replace("Daily", "Changed")),
                action("apply")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM downstream", Integer.class));
    }

    @Test void staleGenerationAndContentConflictRetainResultWithoutDraftOverwrite() {
        String raw = raw(reportPayload());
        jdbc.update("UPDATE task SET generation=2 WHERE root_id='root'");
        assertEquals(Status.SUPERSEDED,
                service.applyAutomatic(input(Stage.DAILY_REPORT), raw,
                        action("stale")).status());
        assertEquals("published", jdbc.queryForObject(
                "SELECT publication_state FROM content", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM downstream", Integer.class));
        jdbc.update("UPDATE task SET generation=1,content_revision=5 WHERE root_id='root'");
        assertEquals(Status.CONTENT_REVISION_CONFLICT,
                service.applyAutomatic(input(Stage.DAILY_REPORT), raw,
                        action("conflict")).status());
        assertNotNull(jdbc.queryForObject("SELECT retained_hash FROM task", String.class));
        assertEquals("published", jdbc.queryForObject(
                "SELECT publication_state FROM content", String.class));
    }

    @Test void manualApplyChecksHashRevisionAndEvidence() {
        String raw = raw(reportPayload());
        jdbc.update("UPDATE task SET content_revision=5 WHERE root_id='root'");
        service.applyAutomatic(input(Stage.DAILY_REPORT), raw, action("conflict"));
        String hash = ResultApplicationService.validate(input(Stage.DAILY_REPORT), raw).resultHash();
        assertThrows(ResultApplicationService.Rejected.class,
                () -> service.applyManual(input(Stage.DAILY_REPORT), hash, 0, 5L,
                        "confirmed", false, action("not-admin")));
        assertThrows(ResultApplicationService.Rejected.class,
                () -> service.applyManual(input(Stage.DAILY_REPORT), "b".repeat(64), 0,
                        5L, "confirmed", true, action("wrong-hash")));
        jdbc.update("UPDATE task SET dependencies_valid=false WHERE root_id='root'");
        assertThrows(ResultApplicationService.Rejected.class,
                () -> service.applyManual(input(Stage.DAILY_REPORT), hash, 0, 5L,
                        "confirmed", true, action("invalid-evidence")));
        jdbc.update("UPDATE task SET dependencies_valid=true WHERE root_id='root'");
        jdbc.update("UPDATE content SET body_revision=5 WHERE id='content'");
        assertEquals(Status.APPLIED, service.applyManual(input(Stage.DAILY_REPORT), hash,
                0, 5L, "confirmed", true, action("manual")).status());
        assertEquals("draft", jdbc.queryForObject(
                "SELECT publication_state FROM content", String.class));
    }

    @Test void downstreamFailureRollsBackBodyResultMarkerAndIntent() {
        failDownstream = true;
        assertThrows(IllegalStateException.class, () -> service.applyAutomatic(
                input(Stage.DAILY_REPORT), raw(reportPayload()), action("rollback")));
        assertEquals("published", jdbc.queryForObject(
                "SELECT publication_state FROM content", String.class));
        assertNull(jdbc.queryForObject("SELECT retained_hash FROM task", String.class));
        assertNull(jdbc.queryForObject("SELECT applied_hash FROM task", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM downstream", Integer.class));
    }

    @Test void contentCasLostAfterPrecheckRetainsResultWithoutDownstream() {
        jdbc.update("UPDATE content SET body_revision=5 WHERE id='content'");
        Result result = service.applyAutomatic(input(Stage.DAILY_REPORT),
                raw(reportPayload()), action("late-content-cas"));
        assertEquals(Status.CONTENT_REVISION_CONFLICT, result.status());
        assertNotNull(jdbc.queryForObject("SELECT retained_hash FROM task", String.class));
        assertEquals("published", jdbc.queryForObject(
                "SELECT publication_state FROM content", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM downstream", Integer.class));
    }

    @Test void lostAttemptCannotRetainOrApplyResult() {
        jdbc.update("UPDATE task SET ownership_valid=false WHERE root_id='root'");
        assertThrows(ResultApplicationService.Rejected.class, () -> service.applyAutomatic(
                input(Stage.DAILY_REPORT), raw(reportPayload()), action("late-attempt")));
        assertNull(jdbc.queryForObject("SELECT retained_hash FROM task", String.class));
        assertEquals("published", jdbc.queryForObject(
                "SELECT publication_state FROM content", String.class));
    }

    private static Input input(Stage stage) {
        return new Input("root", "attempt", stage, 1, INPUT_HASH, 0, 4L,
                Set.of("item-1"), Map.of("item-1", Set.of("entity-1")),
                Set.of("item-1"), Set.of("cite-1"), Set.of(), "entity-1",
                "2026-09-29T16:00:00.000000Z", "2026-09-30T16:00:00.000000Z");
    }
    private static String reportPayload() {
        return "{\"kind\":\"report\",\"body\":{\"title\":\"Daily\","
                + "\"sections\":[{\"heading\":\"News\",\"text\":\"One\","
                + "\"citation_ids\":[\"cite-1\"]}],\"citation_ids\":[\"cite-1\"],"
                + "\"type\":\"daily\",\"period_start\":\"2026-09-29T16:00:00.000000Z\","
                + "\"period_end\":\"2026-09-30T16:00:00.000000Z\","
                + "\"timezone\":\"Asia/Shanghai\",\"learning_task\":null}}";
    }
    private static String raw(String payload) {
        JsonNode body = CanonicalJson.parse(payload);
        ObjectNode core = JsonMapper.shared().createObjectNode();
        core.put("schema_version", "1");
        core.put("input_hash", INPUT_HASH);
        core.set("payload", body);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("ai-result-v1\n".getBytes(StandardCharsets.UTF_8));
            sha.update(CanonicalJson.bytes(core));
            core.put("result_hash", HexFormat.of().formatHex(sha.digest()));
            return new String(CanonicalJson.bytes(core), StandardCharsets.UTF_8);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static ActionContext action(String key) { return new ActionContext(key, key, null); }
}
