package com.lianxibu.site.service.records;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.records.JdbcRecordReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.records.PrivateRecordWriteService.Current;
import com.lianxibu.site.service.records.PrivateRecordWriteService.Key;
import com.lianxibu.site.service.records.PrivateRecordWriteService.Value;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class PrivateRecordWriteServiceTest {
    private static final Actor ALICE = new Actor("alice", "user");
    private static final Actor BOB = new Actor("bob", "user");
    private JdbcTemplate jdbc;
    private PrivateRecordWriteService service;
    private RecordReadService reads;
    private Fixture store;

    @Test void unicodeScalarIdentitiesReachProviderRatherThanFailingUtf16Validation() {
        String full = "😀".repeat(64);
        assertNull(service.progress(new Actor(full,"user"),full,full).rate());
        var missing = assertThrows(PrivateRecordWriteService.Rejected.class,
            () -> service.write(ALICE,"node",full,
                write(0,"planned",full,"note",null,null,null,null,null),"unicode-key","csrf","origin"));
        assertEquals("object_missing",missing.getMessage());
        for (String bad : new String[]{"😀".repeat(65),"a\u0000", "\ud800"})
            assertThrows(IllegalArgumentException.class, () -> service.progress(ALICE,"p-a",bad));
    }

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:records-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE user_records(user_id VARCHAR(64),package_id VARCHAR(64),"
                + "object_kind VARCHAR(16),object_id VARCHAR(64),revision BIGINT,status VARCHAR(16),"
                + "self_assessment VARCHAR(16),note CLOB,artifact_url VARCHAR(2000),"
                + "response_text CLOB,confirmed_body_revision BIGINT,updated_at TIMESTAMP,"
                + "is_read BOOLEAN,is_favorite BOOLEAN,is_ignored BOOLEAN,"
                + "PRIMARY KEY(user_id,object_kind,package_id,object_id))");
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,state VARCHAR(16))");
        jdbc.execute("CREATE TABLE learning_items(package_id VARCHAR(64),kind VARCHAR(16),"
                + "id VARCHAR(64),revision BIGINT,visible BOOLEAN,PRIMARY KEY(package_id,kind,id))");
        jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,revision BIGINT)");
        jdbc.update("INSERT INTO learning_packages VALUES('p-a','published')");
        jdbc.update("INSERT INTO learning_packages VALUES('p-b','published')");
        for (String pkg : List.of("p-a", "p-b")) {
            jdbc.update("INSERT INTO learning_items VALUES(?,'node','shared',3,TRUE)", pkg);
            jdbc.update("INSERT INTO learning_items VALUES(?,'question','q',2,TRUE)", pkg);
            jdbc.update("INSERT INTO learning_items VALUES(?,'project','project',5,TRUE)", pkg);
        }
        jdbc.update("INSERT INTO articles VALUES('article',4)");
        var tx = new DataSourceTransactionManager(source);
        reads = new RecordReadService(new JdbcRecordReads(source), tx);
        store = new Fixture();
        service = new PrivateRecordWriteService(store, reads, tx,
                Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test void articleFlagsRemainIndependentOfLearningStatusAndLastWriteWins() {
        var first = service.write(ALICE, "article", "article",
                write(0, "not_started", null, "note\r\nline", true, false, false, null, null),
                "a1", "csrf", "origin");
        assertEquals(1, first.revision());
        assertEquals("note\nline", first.note());
        assertTrue(first.isRead());
        var second = service.write(ALICE, "article", "article",
                write(1, "completed", null, "changed", true, true, false, null, null),
                "a2", "csrf", "origin");
        assertEquals(2, second.revision());
        assertTrue(second.isFavorite());
        assertEquals("completed", reads.current(ALICE, "article", null, "article").orElseThrow().status());
        assertEquals(1, service.write(ALICE, "article", "article",
                write(0, "not_started", null, "note\r\nline", true, false, false, null, null),
                "a1", "csrf", "origin").revision());
        assertThrows(PrivateRecordWriteService.Rejected.class,
                () -> service.write(ALICE, "article", "article",
                        write(1, "learning", null, "stale", false, false, false, null, null),
                        "a3", "csrf", "origin"));
    }

    @Test void questionAndProjectResponsesReplaceCurrentWithoutCompletingNode() {
        var question = service.write(ALICE, "question", "q",
                write(0, "learning", "p-a", "note", null, null, null, "answer 1", null),
                "q1", "csrf", "origin");
        assertEquals("answer 1", question.latestAnswerText());
        service.write(ALICE, "question", "q",
                write(1, "learning", "p-a", "note", null, null, null, "answer 2", null),
                "q2", "csrf", "origin");
        assertEquals("answer 2", reads.current(ALICE, "question", "p-a", "q")
                .orElseThrow().latestAnswerText());
        var project = service.write(ALICE, "project", "project",
                write(0, "learning", "p-a", "note", null, null, null, null,
                        "https://submit.example.invalid/latest"), "p1", "csrf", "origin");
        assertEquals("https://submit.example.invalid/latest", project.latestSubmissionUrl());
        assertEquals("https://submit.example.invalid/latest", reads.current(ALICE, "project", "p-a",
                "project").orElseThrow().latestSubmissionUrl());
        store.routeNodes = List.of("shared", "shared");
        assertEquals(0, service.progress(ALICE, "p-a", "route").completed());
        assertEquals(1, service.progress(ALICE, "p-a", "route").eligible());
        service.write(ALICE, "node", "shared",
                write(0, "completed", "p-a", "done", null, null, null, null, null),
                "n1", "csrf", "origin");
        assertEquals("1", service.progress(ALICE, "p-a", "route").rate());
        store.routeNodes = List.of();
        assertNull(service.progress(ALICE, "p-a", "route").rate());
    }

    @Test void sameIdAcrossPackagesAndUsersIsIsolatedAndContentChangePreservesRecord() {
        service.write(ALICE, "node", "shared",
                write(0, "completed", "p-a", "done", null, null, null, null, null),
                "a", "csrf", "origin");
        assertTrue(reads.current(ALICE, "node", "p-b", "shared").isEmpty());
        assertTrue(reads.current(BOB, "node", "p-a", "shared").isEmpty());
        service.write(BOB, "node", "shared",
                write(0, "planned", "p-a", "bob", null, null, null, null, null),
                "b", "csrf", "origin");
        jdbc.update("UPDATE learning_items SET revision=4 WHERE package_id='p-a' AND kind='node'");
        assertEquals("completed", reads.current(ALICE, "node", "p-a", "shared").orElseThrow().status());
        assertEquals(3, reads.current(ALICE, "node", "p-a", "shared")
                .orElseThrow().confirmedBodyRevision());
        assertEquals("planned", reads.current(BOB, "node", "p-a", "shared").orElseThrow().status());
        assertThrows(IllegalArgumentException.class,
                () -> service.write(ALICE, "node", "shared",
                        write(0, "learning", null, "wrong", null, null, null, null, null),
                        "wrong", "csrf", "origin"));
    }

    private static RecordsService.RecordWrite write(long revision, String status, String packageId,
            String note, Boolean read, Boolean favorite, Boolean ignored,
            String answer, String submission) {
        return new RecordsService.RecordWrite(revision, status, "unassessed", note, null,
                packageId, packageId == null ? "article" : answer != null ? "question"
                        : submission != null ? "project" : "node",
                read, favorite, ignored, answer, submission);
    }

    private final class Fixture implements PrivateRecordWriteService.Store {
        final Map<String, RecordsService.Record> receipts = new HashMap<>();
        final Map<String, String> hashes = new HashMap<>();
        List<String> routeNodes = List.of();
        @Override public RecordsService.Record committed(String key, String hash) {
            if (hashes.containsKey(key) && !hashes.get(key).equals(hash))
                throw new IllegalStateException("action conflict");
            return receipts.get(key);
        }
        @Override public Current currentForUpdate(Key key) {
            var row = new JdbcRecordReads(jdbc.getDataSource()).current(key.userId(), key.kind(),
                    key.packageId(), key.objectId());
            return row == null ? null : new Current(row.revision(), row.status(),
                    row.selfAssessment(), row.note(), row.artifactUrl(), row.responseText(),
                    row.confirmedBodyRevision(), row.isRead(), row.isFavorite(), row.isIgnored());
        }
        @Override public Long visibleBodyRevision(String packageId, String kind, String objectId) {
            if ("article".equals(kind)) {
                return jdbc.query("SELECT revision FROM articles WHERE id=?", (rs, ignored) ->
                        rs.getLong(1), objectId).stream().findFirst().orElse(null);
            }
            return jdbc.query("SELECT i.revision FROM learning_items i JOIN learning_packages p"
                    + " ON p.id=i.package_id WHERE i.package_id=? AND i.kind=? AND i.id=?"
                    + " AND i.visible=TRUE AND p.state='published'", (rs, ignored) ->
                    rs.getLong(1), packageId, kind, objectId).stream().findFirst().orElse(null);
        }
        @Override public void insert(Key key, Value value, String action, String hash) {
            jdbc.update("INSERT INTO user_records VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    key.userId(), key.packageId(), key.kind(), key.objectId(), 1,
                    value.status(), value.assessment(), value.note(), value.artifactUrl(),
                    value.responseText(), value.confirmedBodyRevision(), Timestamp.from(value.updatedAt()),
                    value.read(), value.favorite(), value.ignored());
            receipt(key, value, 1, action, hash);
        }
        @Override public void update(Key key, long expected, Value value, String action, String hash) {
            assertEquals(1, jdbc.update("UPDATE user_records SET revision=revision+1,status=?,"
                    + "self_assessment=?,note=?,artifact_url=?,response_text=?,confirmed_body_revision=?,"
                    + "updated_at=?,is_read=?,is_favorite=?,is_ignored=? WHERE user_id=?"
                    + " AND package_id=? AND object_kind=? AND object_id=? AND revision=?",
                    value.status(), value.assessment(), value.note(), value.artifactUrl(),
                    value.responseText(), value.confirmedBodyRevision(), Timestamp.from(value.updatedAt()),
                    value.read(), value.favorite(), value.ignored(), key.userId(), key.packageId(),
                    key.kind(), key.objectId(), expected));
            receipt(key, value, expected + 1, action, hash);
        }
        private void receipt(Key key, Value value, long revision, String action, String hash) {
            receipts.put(action, new RecordsService.Record("article".equals(key.kind()) ? null
                    : key.packageId(), key.kind(), key.objectId(), revision, value.status(),
                    value.assessment(), value.note(), value.artifactUrl(),
                    "question".equals(key.kind()) ? value.responseText() : null,
                    "project".equals(key.kind()) ? value.responseText() : null,
                    value.confirmedBodyRevision(), value.updatedAt(), value.read(),
                    value.favorite(), value.ignored()));
            hashes.put(action, hash);
        }
        @Override public List<String> routeNodes(String packageId, String routeId) {
            return routeNodes;
        }
        @Override public Set<String> completedNodes(String user, String pkg, Set<String> ids) {
            Set<String> completed = new HashSet<>();
            for (String id : ids) {
                var row = new JdbcRecordReads(jdbc.getDataSource()).current(user, "node", pkg, id);
                if (row != null && "completed".equals(row.status())) completed.add(id);
            }
            return completed;
        }
    }
}
