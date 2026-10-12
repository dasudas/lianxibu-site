package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class GenerationSettingsPreparationTest {
    JdbcTemplate jdbc;
    SharedWriteExecutor writes;
    FrozenGenerationSettings settings = new FrozenGenerationSettings(3, List.of("fictional-focus"), List.of("fictional-block"));
    int captures;
    boolean failRequest;
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T21:00:00Z"), ZoneOffset.UTC);
    FrozenStore store;

    @BeforeEach void setup() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:settings-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)"); jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE prepared(action_key VARCHAR(128) PRIMARY KEY,body CLOB)");
        writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(ds), new DataSourceTransactionManager(ds), () -> 25, ignored -> {});
        store = new FrozenStore() {
            public Frozen committed(String key) {
                var rows = jdbc.query("SELECT body FROM prepared WHERE action_key=?", (rs, n) -> JsonMapper.shared().readValue(rs.getString(1), Frozen.class), key);
                return rows.isEmpty() ? null : rows.getFirst();
            }
            public void save(Frozen value, String key) { jdbc.update("INSERT INTO prepared VALUES(?,?)", key, JsonMapper.shared().writeValueAsString(value)); }
        };
    }
    GenerationFlowService service(Stage stage) {
        var reports = new ReportInputSnapshotService((command, cutoff) -> new ReportInputSnapshotService.SourceProof(
                cutoff, LocalDate.of(2026,1,1), LocalDate.of(2026,1,2), true, List.of(), List.of(), List.of(LocalDate.of(2026,1,1)), List.of()));
        return new GenerationFlowService(writes, store, (kind, snapshot, hash) -> {
            if (failRequest) throw new IllegalStateException("fictional request failure");
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            var captured = FrozenGenerationSettings.read(CanonicalJson.parse(snapshot).get(FrozenGenerationSettings.FIELD));
            assertEquals(settings, captured);
            return "{\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"fictional\"}],\"parameters\":{},\"response_format\":{\"type\":\"json_object\"},\"prompt_version\":\"fictional-v1\",\"schema_version\":\"1\"}";
        }, clock, () -> true, stage.report() ? reports : null, (root, command, cutoff, snapshot) -> CanonicalJson.parse(stage.report()
                ? "{\"public_metadata_v1\":{\"schema_version\":\"frozen-public-metadata-v1\",\"citations\":[],\"interpretation\":null}}" : "{}"), () -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); captures++; return settings;
        });
    }
    Prepare command(Stage stage, String hash) {
        String json = FrozenApplicationContextTest.context(stage)
                .replace("[\"fictional-citation\"]", "[]")
                .replace("[{\"kind\":\"article_revision\",\"id\":\"fictional-citation\"}]", "[]");
        return new Prepare("fictional-"+stage, 1, stage, LocalDate.of(2026,1,1), null, List.of(),
                List.of(new Evidence("fictional-input", "article", LocalDate.of(2026,1,1), Instant.parse("2026-01-01T12:00:00Z"), "a".repeat(64), "{\"title\":\"Fictional\"}",true,false,null,List.of())),
                List.of(), false, hash, FrozenApplicationContext.fromJson(CanonicalJson.parse(json)));
    }
    SharedWriteExecutor.ActionContext action(String key) { return new SharedWriteExecutor.ActionContext(key, "fictional-root-"+key, null); }

    @Test void persistedSnapshotAndRequestSurviveChangedPreferencesAndReconstruction() {
        var original = settings;
        var cmd = command(Stage.AD_HOC_AI, settings.semanticHash());
        var frozen = service(Stage.AD_HOC_AI).prepare(cmd, action("stable"));
        assertEquals(original, FrozenGenerationSettings.read(CanonicalJson.parse(frozen.snapshotJson()).get(FrozenGenerationSettings.FIELD)));
        assertEquals(CanonicalJson.hash(CanonicalJson.Domain.INPUT, CanonicalJson.parse(frozen.snapshotJson())), frozen.inputHash());
        settings = new FrozenGenerationSettings(4, List.of("fictional-new"), List.of());
        assertEquals(frozen, service(Stage.AD_HOC_AI).prepare(cmd, action("stable")));
        assertEquals(1, captures);
        assertThrows(InputRejected.class, () -> service(Stage.AD_HOC_AI).prepare(cmd, action("stale")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM prepared", Integer.class));
    }
    @Test void requestFailureLeavesNoPreparedRowAndClosedReportCoverageAlsoCapturesSettings() {
        failRequest = true;
        assertThrows(IllegalStateException.class, () -> service(Stage.AD_HOC_AI).prepare(command(Stage.AD_HOC_AI, settings.semanticHash()), action("failed")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM prepared", Integer.class));
        failRequest = false;
        var report = service(Stage.DAILY_REPORT).prepare(command(Stage.DAILY_REPORT, settings.semanticHash()), action("report"));
        assertNull(report.requestJson());
        assertEquals(settings, FrozenGenerationSettings.read(CanonicalJson.parse(report.snapshotJson()).get(FrozenGenerationSettings.FIELD)));
        assertEquals(2, captures);
    }
}
