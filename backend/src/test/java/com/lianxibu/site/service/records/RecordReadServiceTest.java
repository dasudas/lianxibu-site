package com.lianxibu.site.service.records;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.records.JdbcRecordReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class RecordReadServiceTest {
    private JdbcTemplate jdbc;
    private RecordReadService reads;
    private final Actor alice = new Actor("alice", "user");
    private final Actor bob = new Actor("bob", "user");

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:records-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE user_records(user_id VARCHAR(64),object_kind VARCHAR(16),"
                + "package_id VARCHAR(64),object_id VARCHAR(64),status VARCHAR(32),"
                + "self_assessment VARCHAR(32),is_read BOOLEAN,is_favorite BOOLEAN,is_ignored BOOLEAN,"
                + "note CLOB,artifact_url VARCHAR(2000),response_text CLOB,revision BIGINT,"
                + "updated_at TIMESTAMP,confirmed_body_revision BIGINT,"
                + "PRIMARY KEY(user_id,object_kind,package_id,object_id))");
        reads = new RecordReadService(new JdbcRecordReads(source), new DataSourceTransactionManager(source));
    }

    @Test void completeCompositeKeySeparatesUsersAndPackages() {
        row("alice", "question", "p1", "same", "Alice answer", "Alice note");
        row("bob", "question", "p1", "same", "Bob answer", "Bob note");
        row("alice", "question", "p2", "same", "Other package", "Other note");
        assertEquals("Alice answer", reads.current(alice, "question", "p1", "same")
                .orElseThrow().latestAnswerText());
        assertEquals("Bob answer", reads.current(bob, "question", "p1", "same")
                .orElseThrow().latestAnswerText());
        assertEquals("Other package", reads.current(alice, "question", "p2", "same")
                .orElseThrow().latestAnswerText());
        assertEquals(1, reads.list(alice, "p1", "question", 1, 10).total());
        assertThrows(IllegalArgumentException.class,
                () -> reads.current(alice, "question", null, "same"));
    }

    @Test void missingPackageCannotReadLearningRecordAndArticleUsesPhysicalEmptyKey() {
        row("alice", "node", "p1", "same", null, "Progress");
        row("alice", "article", "", "same", null, "Private article note");
        assertEquals(0, reads.list(alice, null, "node", 1, 10).total());
        assertEquals(1, reads.list(alice, null, null, 1, 10).total());
        var article = reads.current(alice, "article", null, "same").orElseThrow();
        assertNull(article.packageId());
        assertEquals("not_started", article.status());
        assertTrue(article.isRead());
        assertEquals("Private article note", article.note());
        assertThrows(IllegalArgumentException.class,
                () -> reads.current(alice, "node", null, "same"));
    }

    @Test void latestSubmissionIsCurrentOnlyAndAnonymousIsRejected() {
        row("alice", "project", "p1", "project-one", null, "Project note");
        var record = reads.current(alice, "project", "p1", "project-one").orElseThrow();
        assertEquals("https://example.invalid/fixture", record.latestSubmissionUrl());
        assertNull(record.latestAnswerText());
        assertEquals(4L, record.confirmedBodyRevision());
        assertFalse(record.isRead());
        assertThrows(RuntimeException.class, () -> reads.list(null, "p1", "project", 1, 10));
        assertThrows(RuntimeException.class,
                () -> reads.write(alice, "project", "project-one", null, "key", "csrf", "origin"));
    }

    private void row(String user, String kind, String packageId, String objectId,
                     String response, String note) {
        jdbc.update("INSERT INTO user_records(user_id,object_kind,package_id,object_id,status,"
                        + "self_assessment,is_read,is_favorite,is_ignored,note,artifact_url,"
                        + "response_text,revision,updated_at,confirmed_body_revision) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                user, kind, packageId, objectId, "", null, true, false, false,
                note, "https://example.invalid/fixture", response, 2,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), 4L);
    }
}
