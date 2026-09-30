package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.repository.sources.JdbcSourceReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.SourceAdmissionPolicy.ProbeEvidence;
import com.lianxibu.site.service.sources.SourceAdmissionPolicy.PromotionEvidence;
import com.lianxibu.site.service.sources.SourceAdmissionPolicy.TrialDay;
import com.lianxibu.site.service.sources.SourceIdentityMappingService.ReviewedMapping;
import com.lianxibu.site.service.sources.SourcesService.SourceConfig;
import com.lianxibu.site.service.sources.SourcesService.SourceCreate;
import com.lianxibu.site.service.sources.SourcesService.SourceWrite;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SourceAdmissionServiceTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Actor owner = new Actor("fixture-owner", "owner");
    private JdbcTemplate jdbc;
    private JdbcSourceAdmission store;
    private SourceAdmissionService service;
    private SourceAdmissionPolicy policy;
    private String configJson;
    private String configHash;
    private final Evidence evidence = new Evidence();

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-admit-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(?)", 1);
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),"
                + "name VARCHAR(200),state VARCHAR(16),config_json CLOB,probe_json CLOB,"
                + "revision BIGINT,updated_at TIMESTAMP,draft_config_json CLOB,"
                + "effective_config_json CLOB,config_revision BIGINT,activation_stage VARCHAR(16),"
                + "config_valid_until TIMESTAMP,category VARCHAR(16))");
        jdbc.execute("CREATE TABLE source_action_receipts(action_key VARCHAR(128) PRIMARY KEY,"
                + "source_id VARCHAR(80),revision BIGINT,config_revision BIGINT,"
                + "activation_stage VARCHAR(16),action_name VARCHAR(32))");
        store = new JdbcSourceAdmission(source);
        policy = new SourceAdmissionPolicy(Clock.fixed(now, ZoneOffset.UTC));
        configJson = policy.validatedConfigJson(config("fixture-parser"));
        configHash = SourceIdentityMappingService.sha256(configJson);
        var identities = new SourceIdentityMappingService(new JdbcSourceReads(source),
                new DataSourceTransactionManager(source), ClockHolder.mapping());
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                new DataSourceTransactionManager(source), () -> 25, ignored -> { });
        service = new SourceAdmissionService(store, identities, writes, policy, evidence,
                new SourceAdmissionService.ActionReceipts() {
                    @Override public SourceAdmissionService.Decision committed(String key) {
                        var rows = jdbc.query("SELECT source_id,revision,config_revision,"
                                + "activation_stage,action_name FROM source_action_receipts"
                                + " WHERE action_key=?", (rs, ignored) -> new SourceAdmissionService.Decision(
                                rs.getString(1), rs.getLong(2), rs.getLong(3),
                                rs.getString(4), rs.getString(5)), key);
                        return rows.isEmpty() ? null : rows.getFirst();
                    }
                    @Override public void record(String key, SourceAdmissionService.Decision value) {
                        jdbc.update("INSERT INTO source_action_receipts VALUES(?,?,?,?,?,?)",
                                key, value.sourceId(), value.revision(), value.configRevision(),
                                value.activationStage(), value.action());
                    }
                }, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void disabledProbeTrialProductionAndEditStopsOldFetch() {
        var created = create();
        assertEquals("disabled", created.activationStage());
        assertEquals(configJson, store.locked("fixture:feed").legacyConfigJson());
        assertEquals(created, service.create(owner,
                new SourceCreate("fixture:feed", "Fixture", "news_tech", config("fixture-parser")),
                action("create")));
        var ticket = service.requestProbe(owner, "fixture:feed", created.revision(), action("probe"));
        assertEquals(configHash, ticket.configSha256());
        assertEquals("disabled", store.locked("fixture:feed").activationStage());
        evidence.probe = validProbe();
        var trial = service.startTrial(owner, "fixture:feed", created.revision(), action("trial"));
        assertEquals("trial", trial.activationStage());
        assertEquals(configJson, store.locked("fixture:feed").effectiveConfigJson());
        evidence.promotion = promotion(validDays(), "usage-reviewed");
        var production = service.promote(owner, "fixture:feed", trial.revision(), action("promote"));
        assertEquals("production", production.activationStage());
        assertEquals(production, service.promote(owner, "fixture:feed", trial.revision(), action("promote")));
        var edited = service.edit(owner, "fixture:feed",
                new SourceWrite(production.revision(), "Fixture edited", "news_tech",
                        config("fixture-parser-v2")), action("edit"));
        assertEquals("disabled", edited.activationStage());
        assertEquals(2, edited.configRevision());
        assertEquals(configJson, store.locked("fixture:feed").legacyConfigJson());
        assertEquals(configJson, store.locked("fixture:feed").effectiveConfigJson());
        assertNotNull(store.locked("fixture:feed").draftConfigJson());
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.promote(owner, "fixture:feed", edited.revision(), action("stale-promote")));
    }

    @Test void sampleUsageAndSevenDayBoundariesDenyPromotion() {
        var created = create();
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.startTrial(owner, "fixture:feed", created.revision(), action("trial-no-probe")));
        evidence.probe = new ProbeEvidence(1, configHash, "fixture-parser", true, true,
                true, true, true, 2);
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.startTrial(owner, "fixture:feed", created.revision(), action("trial-two-samples")));
        evidence.probe = validProbe();
        var trial = service.startTrial(owner, "fixture:feed", created.revision(), action("trial-good"));
        evidence.promotion = promotion(validDays().subList(0, 6), "usage-reviewed");
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.promote(owner, "fixture:feed", trial.revision(), action("six-days")));
        evidence.promotion = promotion(validDays(), null);
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.promote(owner, "fixture:feed", trial.revision(), action("no-usage")));
        var failedDays = new ArrayList<>(validDays());
        failedDays.set(3, new TrialDay(failedDays.get(3).businessDate(), 1, "fixture-parser",
                20, 19, 3, 0, 0, true));
        evidence.promotion = promotion(failedDays, "usage-reviewed");
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.promote(owner, "fixture:feed", trial.revision(), action("parse-failures")));
        var crossedMidnight = new ArrayList<>(validDays());
        crossedMidnight.set(2, new TrialDay(crossedMidnight.get(2).businessDate(), 1,
                "fixture-parser", 20, 19, 2, 0, 2, true));
        crossedMidnight.set(3, new TrialDay(crossedMidnight.get(3).businessDate(), 1,
                "fixture-parser", 20, 19, 1, 1, 0, true));
        evidence.promotion = promotion(crossedMidnight, "usage-reviewed");
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.promote(owner, "fixture:feed", trial.revision(), action("cross-day-failures")));
        var lowSuccess = new ArrayList<>(validDays());
        lowSuccess.set(3, new TrialDay(lowSuccess.get(3).businessDate(), 1, "fixture-parser",
                20, 18, 1, 0, 0, true));
        evidence.promotion = promotion(lowSuccess, "usage-reviewed");
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.promote(owner, "fixture:feed", trial.revision(), action("below-95")));
        evidence.promotion = new PromotionEvidence(0, configHash, "fixture-parser",
                "usage-reviewed", "field-map-reviewed", validProbe(), validDays());
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.promote(owner, "fixture:feed", trial.revision(), action("stale-config")));
        evidence.promotion = promotion(validDays(), "usage-reviewed");
        assertEquals("production", service.promote(owner, "fixture:feed", trial.revision(),
                action("valid-promote")).activationStage());
    }

    @Test void oldEnabledRowIsNotPromotedAndNonOwnerCannotMutate() {
        var created = create();
        jdbc.update("UPDATE sources SET state=? WHERE id=?", "enabled", "fixture:feed");
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.promote(owner, "fixture:feed", created.revision(), action("old-enabled")));
        assertEquals("disabled", store.locked("fixture:feed").activationStage());
        assertThrows(SourceAdmissionService.AdmissionDenied.class,
                () -> service.disable(new Actor("reader", "reader"), "fixture:feed",
                        created.revision(), action("reader-disable")));
    }

    private SourceAdmissionService.Decision create() {
        return service.create(owner,
                new SourceCreate("fixture:feed", "Fixture", "news_tech", config("fixture-parser")),
                action("create"));
    }
    private static ActionContext action(String key) {
        return new ActionContext("fixture-" + key, "fixture:feed", "fixture-snapshot");
    }
    private SourceConfig config(String parser) {
        return new SourceConfig("fixture-rss", List.of("https://feeds.example.invalid/news"),
                "fixture-id", "daily", parser, 100, 100_000,
                List.of("feeds.example.invalid"), now.plusSeconds(86400 * 30), null);
    }
    private ProbeEvidence validProbe() {
        return new ProbeEvidence(1, configHash, "fixture-parser", true, true,
                true, true, true, 3);
    }
    private List<TrialDay> validDays() {
        List<TrialDay> days = new ArrayList<>();
        LocalDate first = LocalDate.of(2025, 12, 25);
        for (int index = 0; index < 7; index++) {
            days.add(new TrialDay(first.plusDays(index), 1, "fixture-parser", 20, 19,
                    1, 0, 0, true));
        }
        return days;
    }
    private PromotionEvidence promotion(List<TrialDay> days, String usageRef) {
        return new PromotionEvidence(1, configHash, "fixture-parser", usageRef,
                "field-map-reviewed", validProbe(), days);
    }
    private static final class Evidence implements SourceAdmissionService.EvidenceStore {
        ProbeEvidence probe;
        PromotionEvidence promotion;
        @Override public ProbeEvidence probe(String sourceId, long version) { return probe; }
        @Override public PromotionEvidence promotion(String sourceId, long version) { return promotion; }
    }
    private static final class ClockHolder {
        static List<ReviewedMapping> mapping() {
            var policy = new SourceAdmissionPolicy(Clock.fixed(
                    Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
            String config = policy.validatedConfigJson(new SourceConfig("fixture-rss",
                    List.of("https://feeds.example.invalid/news"), "fixture-id", "daily",
                    "fixture-parser", 100, 100_000, List.of("feeds.example.invalid"),
                    Instant.parse("2026-01-31T00:00:00Z"), null));
            return List.of(new ReviewedMapping("fixture:feed", "article",
                    SourceIdentityMappingService.sha256(config),
                    "https://feeds.example.invalid/news", "news_tech"));
        }
    }
}
