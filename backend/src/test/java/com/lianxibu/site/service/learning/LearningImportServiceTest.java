package com.lianxibu.site.service.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.learning.LearningImportService.Part;
import com.lianxibu.site.service.learning.LearningImportService.Plan;
import com.lianxibu.site.service.learning.LearningImportService.Result;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;

class LearningImportServiceTest {
    @TempDir Path temporary;
    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private JdbcTemplate jdbc;
    private FileLearningImportStages stages;
    private LearningImportService service;
    private SharedWriteExecutor writes;
    private CommitStore commits;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:import-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE packages(id VARCHAR(64) PRIMARY KEY,revision BIGINT,state VARCHAR(16),title VARCHAR(512))");
        jdbc.execute("CREATE TABLE items(package_id VARCHAR(64),id VARCHAR(64),state VARCHAR(16),"
                + "body CLOB,PRIMARY KEY(package_id,id))");
        jdbc.execute("CREATE TABLE receipts(action_key VARCHAR(128) PRIMARY KEY,"
                + "command_hash VARCHAR(64),package_id VARCHAR(64),revision BIGINT)");
        writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                new DataSourceTransactionManager(source));
        stages = new FileLearningImportStages(temporary.resolve("stages"));
        commits = new CommitStore();
        service = new LearningImportService(stages, commits, writes,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void twoPartCreateCommitAndReceiptReplayAfterStageCleanup() {
        var first = chunk("i-1", 0, 2, node("tmp", "n-1"));
        var second = chunk("i-1", 1, 2, node("tmp", "n-2"));
        assertEquals(first.partHash(), service.stage("owner", null, first).partHash());
        service.stage("owner", null, second);
        var manifest = manifest("i-1", first, second);
        Result result = service.commit("owner", null, null, manifest, action("commit", "new-package"));
        assertEquals("new-package", result.packageId());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM items", Integer.class));
        assertEquals("draft", jdbc.queryForObject("SELECT state FROM packages WHERE id='new-package'", String.class));
        assertThrows(LearningImportService.Rejected.class,
                () -> stages.load("owner", null, "i-1", NOW));
        assertTrue(service.commit("owner", null, null, manifest,
                action("commit", "new-package")).replayed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    }

    @Test void hashConflictMissingPartCrossUserAndExpiryAreRejected() {
        var first = chunk("i-1", 0, 2, node("tmp", "n-1"));
        service.stage("owner", null, first);
        assertThrows(LearningImportService.Rejected.class,
                () -> service.stage("owner", null, chunk("i-1", 0, 2, node("tmp", "changed"))));
        JsonNode tooLarge = CanonicalJson.parse("{\"package_id\":\"tmp\",\"id\":\"large\",\"text\":\""
                + "x".repeat(256 * 1024) + "\"}");
        assertThrows(LearningImportService.Rejected.class,
                () -> service.stage("owner", null, chunk("i-large", 0, 1, tooLarge)));
        assertThrows(LearningImportService.Rejected.class,
                () -> service.stage("owner", null, new LearningService.ImportChunk("i-1", 1, 2,
                        "0".repeat(64), List.of(node("tmp", "n-2")))));
        assertThrows(LearningImportService.Rejected.class,
                () -> service.stage("other", null, first));
        assertThrows(LearningImportService.Rejected.class,
                () -> service.commit("owner", null, null, manifest("i-1", first), action("c", "new")));
        var expired = new LearningImportService(stages, commits, writes,
                Clock.fixed(NOW.plus(25, ChronoUnit.HOURS), ZoneOffset.UTC));
        assertThrows(LearningImportService.Rejected.class,
                () -> expired.commit("owner", null, null, manifest("i-1", first), action("late", "new")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM packages", Integer.class));
    }

    @Test void updateCasFailureKeepsStageAndOtherItemsAndPrivateRows() {
        jdbc.update("INSERT INTO packages VALUES('existing',7,'published','Before')");
        jdbc.update("INSERT INTO items VALUES('existing','old','published','keep')");
        var part = chunk("i-update", 0, 1, node("tmp", "new"));
        service.stage("owner", "existing", part);
        var manifest = manifest("i-update", part);
        assertThrows(LearningImportService.Rejected.class,
                () -> service.commit("owner", "existing", 6L, manifest, action("bad", "existing")));
        assertEquals(1, stages.load("owner", "existing", "i-update", NOW).parts().size());
        Result accepted = service.commit("owner", "existing", 7L, manifest,
                action("good", "existing"));
        assertEquals(8, accepted.revision());
        assertEquals("keep", jdbc.queryForObject("SELECT body FROM items WHERE id='old'", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM items", Integer.class));
        assertEquals("draft", jdbc.queryForObject("SELECT state FROM packages WHERE id='existing'", String.class));
    }

    @Test void duplicateIdentityAndMixedTemporaryPackageFailBeforeWrites() {
        var duplicate = chunk("i-dupe", 0, 1, node("tmp", "n"), node("tmp", "n"));
        service.stage("owner", null, duplicate);
        assertThrows(LearningImportService.Rejected.class,
                () -> service.commit("owner", null, null, manifest("i-dupe", duplicate),
                        action("dupe", "new")));
        stages.clear("owner", null, "i-dupe");
        var mixed = chunk("i-mixed", 0, 1, node("tmp-a", "a"), node("tmp-b", "b"));
        service.stage("owner", null, mixed);
        assertThrows(LearningImportService.Rejected.class,
                () -> service.commit("owner", null, null, manifest("i-mixed", mixed),
                        action("mixed", "new")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM packages", Integer.class));
    }

    @Test void writeFailureRollsBackAndKeepsStageForRetry() {
        var part = chunk("i-fault", 0, 1, node("tmp", "n"));
        service.stage("owner", null, part);
        var manifest = manifest("i-fault", part);
        commits.failBeforeReceipt = true;
        assertThrows(IllegalStateException.class,
                () -> service.commit("owner", null, null, manifest, action("fault", "new")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM packages", Integer.class));
        assertEquals(1, stages.load("owner", null, "i-fault", NOW).parts().size());
        commits.failBeforeReceipt = false;
        assertEquals("new", service.commit("owner", null, null, manifest,
                action("retry", "new")).packageId());
    }

    @Test void committedReceiptRecoversAfterCleanupFailure() {
        var part = chunk("i-cleanup", 0, 1, node("tmp", "n"));
        service.stage("owner", null, part);
        var manifest = manifest("i-cleanup", part);
        LearningImportService.StageStore interrupted = new LearningImportService.StageStore() {
            boolean fail = true;
            @Override public LearningImportService.Staged save(String actor, String target,
                    String importId, Part value, Instant now) {
                return stages.save(actor, target, importId, value, now);
            }
            @Override public LearningImportService.Staged load(String actor, String target,
                    String importId, Instant now) {
                return stages.load(actor, target, importId, now);
            }
            @Override public void clear(String actor, String target, String importId) {
                if (fail) { fail = false; throw new IllegalStateException("cleanup interrupted"); }
                stages.clear(actor, target, importId);
            }
        };
        var retryable = new LearningImportService(interrupted, commits, writes,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals("new", retryable.commit("owner", null, null, manifest,
                action("commit", "new")).packageId());
        assertEquals(1, stages.load("owner", null, "i-cleanup", NOW).parts().size());
        assertTrue(retryable.commit("owner", null, null, manifest,
                action("commit", "new")).replayed());
        assertThrows(LearningImportService.Rejected.class,
                () -> stages.load("owner", null, "i-cleanup", NOW));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM packages", Integer.class));
    }

    @Test void knownZeroRevisionImportsOnceAndPreservesExistingItems() {
        jdbc.update("INSERT INTO packages VALUES('existing',0,'draft','Before')");
        jdbc.update("INSERT INTO items VALUES('existing','old','published','keep')");
        var part = chunk("i-zero", 0, 1, node("tmp", "new"));
        service.stage("owner", "existing", part);
        var manifest = manifest("i-zero", part);
        Result accepted = service.commit("owner", "existing", 0L, manifest, action("zero", "existing"));
        assertEquals(1, accepted.revision());
        assertTrue(service.commit("owner", "existing", 0L, manifest, action("zero", "existing")).replayed());
        assertEquals(1, jdbc.queryForObject("SELECT revision FROM packages WHERE id='existing'", Long.class));
        assertEquals("keep", jdbc.queryForObject("SELECT body FROM items WHERE id='old'", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM items", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    }

    @Test void unknownNegativeOrUnsafeIncrementRejectsBeforeWritesAndKeepsStage() {
        jdbc.update("INSERT INTO packages VALUES('existing',0,'draft','Before')");
        var part = chunk("i-boundary", 0, 1, node("tmp", "new"));
        service.stage("owner", "existing", part);
        var manifest = manifest("i-boundary", part);
        for (Long revision : new Long[]{null, -1L, 9_007_199_254_740_991L, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> service.commit("owner", "existing", revision,
                    manifest, action("bad-" + revision, "existing")));
        }
        assertThrows(IllegalArgumentException.class, () -> service.commit("owner", null, 0L,
                manifest, action("new-with-revision", "new")));
        assertEquals(0L, jdbc.queryForObject("SELECT revision FROM packages WHERE id='existing'", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM items", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
        assertEquals(1, stages.load("owner", "existing", "i-boundary", NOW).parts().size());
    }

    private static LearningService.ImportChunk chunk(String importId, long no, long count, JsonNode... items) {
        List<JsonNode> values = List.of(items);
        return new LearningService.ImportChunk(importId, no, count,
                LearningImportService.partHash(importId, no, count, values), values);
    }
    private static LearningService.ImportFinalize manifest(String importId, LearningService.ImportChunk... chunks) {
        List<Part> parts = java.util.Arrays.stream(chunks).map(chunk -> new Part(
                chunk.partNo(), chunk.partCount(), chunk.partHash(), chunk.items())).toList();
        return new LearningService.ImportFinalize(importId, "Fixture", "Description",
                chunks[0].partCount(), LearningImportService.manifestHash(importId, parts));
    }
    private static JsonNode node(String packageId, String id) {
        return CanonicalJson.parse("{\"package_id\":\"" + packageId + "\",\"id\":\"" + id
                + "\",\"title\":\"Fixture\",\"scope\":\"Test\"}");
    }
    private static ActionContext action(String key, String resource) {
        return new ActionContext(key, resource, null);
    }

    private final class CommitStore implements LearningImportService.CommitStore {
        boolean failBeforeReceipt;
        @Override public Result committed(String key, String hash) {
            var rows = jdbc.query("SELECT command_hash,package_id,revision FROM receipts WHERE action_key=?",
                    (rs, ignored) -> new Object[]{rs.getString(1), new Result(rs.getString(2),
                            rs.getLong(3), false)}, key);
            if (rows.isEmpty()) return null;
            if (!hash.equals(rows.getFirst()[0])) throw new IllegalStateException("action conflict");
            return (Result) rows.getFirst()[1];
        }
        @Override public Result apply(Plan plan, String actor, Long expected,
                                      String hash, ActionContext action) {
            String packageId = plan.targetPackageId() == null
                    ? action.preallocatedResourceId() : plan.targetPackageId();
            long revision;
            if (expected == null) {
                jdbc.update("INSERT INTO packages VALUES(?,1,'draft',?)", packageId, plan.title());
                revision = 1;
            } else {
                int changed = jdbc.update("UPDATE packages SET revision=revision+1,state='draft',title=?"
                        + " WHERE id=? AND revision=?", plan.title(), packageId, expected);
                if (changed != 1) throw new LearningImportService.Rejected("revision_conflict");
                revision = expected + 1;
            }
            for (JsonNode item : plan.items()) {
                if (!item.has("id")) continue;
                jdbc.update("INSERT INTO items VALUES(?,?,?,?)", packageId,
                        item.get("id").asText(), "draft", new String(CanonicalJson.bytes(item)));
            }
            if (failBeforeReceipt) throw new IllegalStateException("fixture write interrupted");
            jdbc.update("INSERT INTO receipts VALUES(?,?,?,?)", action.actionKey(), hash,
                    packageId, revision);
            return new Result(packageId, revision, false);
        }
    }
}
