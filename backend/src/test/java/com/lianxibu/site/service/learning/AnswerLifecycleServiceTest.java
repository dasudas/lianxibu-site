package com.lianxibu.site.service.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.learning.AnswerLifecycleService.Action;
import com.lianxibu.site.service.learning.AnswerLifecycleService.Current;
import com.lianxibu.site.service.learning.AnswerLifecycleService.Result;
import com.lianxibu.site.service.learning.AnswerLifecycleService.Store;
import java.util.Arrays;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class AnswerLifecycleServiceTest {
    private JdbcTemplate jdbc;
    private AnswerLifecycleService service;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:answer-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,state VARCHAR(16),revision BIGINT)");
        jdbc.execute("CREATE TABLE learning_items(package_id VARCHAR(64),kind VARCHAR(16),"
                + "id VARCHAR(64),body_json CLOB,answer_text CLOB,answer_state VARCHAR(16),"
                + "answer_generation_state VARCHAR(32),answer_revision BIGINT,"
                + "answer_review_hash_v1 BINARY(32),revision BIGINT,visible BOOLEAN,"
                + "PRIMARY KEY(package_id,kind,id))");
        jdbc.execute("CREATE TABLE answer_receipts(action_key VARCHAR(128) PRIMARY KEY,"
                + "command_hash VARCHAR(64),package_id VARCHAR(64),question_id VARCHAR(64),"
                + "revision BIGINT,answer_revision BIGINT,state VARCHAR(16))");
        jdbc.update("INSERT INTO learning_packages VALUES('p-a','published',4)");
        jdbc.update("INSERT INTO learning_packages VALUES('p-b','published',9)");
        jdbc.update("INSERT INTO learning_items VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                "p-a", "question", "q", "{\"question\":\"Fixture\"}", null,
                null, "not_generated", null, null, 1L, true);
        jdbc.update("INSERT INTO learning_items VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                "p-b", "question", "q", "{\"question\":\"Other\"}", null,
                null, "not_generated", null, null, 1L, true);
        service = new AnswerLifecycleService(new SharedWriteExecutor(
                new JdbcSharedWriteEntry(source), new DataSourceTransactionManager(source)),
                new Fixture());
    }

    @Test void editApprovePublishAndEditOnlyWithdrawsAnswer() {
        Result edited = service.edit("p-a", "q", 1, "first\r\nanswer", action("edit"));
        assertEquals("draft", edited.answerState());
        assertEquals("first\nanswer", answerText("p-a"));
        assertNull(service.publicAnswer("p-a", "q"));
        service.action("p-a", "q", 2, Action.APPROVE, "review", action("approve"));
        assertNull(service.publicAnswer("p-a", "q"));
        service.action("p-a", "q", 3, Action.PUBLISH, "publish", action("publish"));
        assertEquals("first\nanswer", service.publicAnswer("p-a", "q"));
        service.edit("p-a", "q", 4, "updated", action("edit2"));
        assertNull(service.publicAnswer("p-a", "q"));
        assertEquals("published", jdbc.queryForObject("SELECT state FROM learning_packages WHERE id='p-a'",
                String.class));
        assertEquals("{\"question\":\"Fixture\"}", jdbc.queryForObject(
                "SELECT body_json FROM learning_items WHERE package_id='p-a' AND id='q'", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT answer_revision FROM learning_items"
                + " WHERE package_id='p-a' AND id='q'", Long.class));
    }

    @Test void staleHashAndRevisionCannotPublishAndWithdrawHidesAnswer() {
        service.edit("p-a", "q", 1, "body", action("edit"));
        service.action("p-a", "q", 2, Action.APPROVE, "review", action("approve"));
        assertThrows(AnswerLifecycleService.Rejected.class,
                () -> service.action("p-a", "q", 2, Action.PUBLISH, "stale", action("stale")));
        jdbc.update("UPDATE learning_items SET answer_text='tampered' WHERE package_id='p-a' AND id='q'");
        assertThrows(AnswerLifecycleService.Rejected.class,
                () -> service.action("p-a", "q", 3, Action.PUBLISH, "publish", action("publish")));
        jdbc.update("UPDATE learning_items SET answer_text='body' WHERE package_id='p-a' AND id='q'");
        service.action("p-a", "q", 3, Action.PUBLISH, "publish", action("publish2"));
        jdbc.update("UPDATE learning_items SET answer_text='tampered' WHERE package_id='p-a' AND id='q'");
        assertNull(service.publicAnswer("p-a", "q"));
        jdbc.update("UPDATE learning_items SET answer_text='body' WHERE package_id='p-a' AND id='q'");
        service.action("p-a", "q", 4, Action.WITHDRAW, "withdraw", action("withdraw"));
        assertNull(service.publicAnswer("p-a", "q"));
        assertNull(jdbc.queryForObject("SELECT answer_review_hash_v1 FROM learning_items"
                + " WHERE package_id='p-a' AND id='q'", byte[].class));
    }

    @Test void packageAndQuestionVisibilityGateAnswerWithoutChangingItsState() {
        service.edit("p-a", "q", 1, "visible body", action("edit"));
        service.action("p-a", "q", 2, Action.APPROVE, "review", action("approve"));
        service.action("p-a", "q", 3, Action.PUBLISH, "publish", action("publish"));
        jdbc.update("UPDATE learning_packages SET state='draft' WHERE id='p-a'");
        assertNull(service.publicAnswer("p-a", "q"));
        jdbc.update("UPDATE learning_packages SET state='published' WHERE id='p-a'");
        jdbc.update("UPDATE learning_items SET visible=FALSE WHERE package_id='p-a' AND id='q'");
        assertNull(service.publicAnswer("p-a", "q"));
        jdbc.update("UPDATE learning_items SET visible=TRUE WHERE package_id='p-a' AND id='q'");
        assertEquals("visible body", service.publicAnswer("p-a", "q"));
        assertEquals("published", jdbc.queryForObject("SELECT answer_state FROM learning_items"
                + " WHERE package_id='p-a' AND id='q'", String.class));
    }

    @Test void compositeKeyAndReceiptDoNotLeakAcrossPackages() {
        service.edit("p-a", "q", 1, "only alpha", action("edit"));
        assertNull(answerText("p-b"));
        Result replay = service.edit("p-a", "q", 1, "only alpha", action("edit"));
        assertTrue(replay.replayed());
        assertThrows(IllegalStateException.class,
                () -> service.edit("p-a", "q", 1, "different", action("edit")));
        assertThrows(AnswerLifecycleService.Rejected.class,
                () -> service.action("p-b", "q", 1, Action.PUBLISH, "wrong", action("other")));
    }

    private String answerText(String packageId) {
        return jdbc.queryForObject("SELECT answer_text FROM learning_items WHERE package_id=? AND id='q'",
                String.class, packageId);
    }
    private static ActionContext action(String key) { return new ActionContext(key, "answer", null); }

    private final class Fixture implements Store {
        @Override public Result committed(String key, String hash) {
            var rows = jdbc.query("SELECT command_hash,package_id,question_id,revision,"
                    + "answer_revision,state FROM answer_receipts WHERE action_key=?",
                    (rs, ignored) -> new Object[]{rs.getString(1), new Result(rs.getString(2),
                            rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6), false)}, key);
            if (rows.isEmpty()) return null;
            if (!hash.equals(rows.getFirst()[0])) throw new IllegalStateException("action conflict");
            return (Result) rows.getFirst()[1];
        }
        @Override public Current locked(String packageId, String questionId) {
            return current(packageId, questionId);
        }
        @Override public Current publicSnapshot(String packageId, String questionId) {
            return current(packageId, questionId);
        }
        private Current current(String packageId, String questionId) {
            var rows = jdbc.query("SELECT i.answer_text,i.answer_state,i.revision,i.answer_revision,"
                    + "i.answer_review_hash_v1,p.state,i.visible FROM learning_items i"
                    + " JOIN learning_packages p ON p.id=i.package_id WHERE i.package_id=?"
                    + " AND i.kind='question' AND i.id=?", (rs, ignored) -> new Current(
                    packageId, questionId, rs.getString(1), rs.getString(2), rs.getLong(3),
                    rs.getObject(4) == null ? null : rs.getLong(4), rs.getBytes(5),
                    "published".equals(rs.getString(6)), rs.getBoolean(7)), packageId, questionId);
            return rows.isEmpty() ? null : rows.getFirst();
        }
        @Override public void edit(Current row, String answer, long nextAnswerRevision,
                                   String hash, String key) {
            assertEquals(1, jdbc.update("UPDATE learning_items SET answer_text=?,answer_state='draft',"
                    + "answer_revision=?,answer_review_hash_v1=NULL,revision=revision+1"
                    + " WHERE package_id=? AND kind='question' AND id=? AND revision=?",
                    answer, nextAnswerRevision, row.packageId(), row.questionId(), row.revision()));
            receipt(row, nextAnswerRevision, "draft", hash, key);
        }
        @Override public void transition(Current row, String state, byte[] reviewed,
                                         String hash, String key) {
            assertEquals(1, jdbc.update("UPDATE learning_items SET answer_state=?,"
                    + "answer_review_hash_v1=?,revision=revision+1 WHERE package_id=?"
                    + " AND kind='question' AND id=? AND revision=?", state, reviewed,
                    row.packageId(), row.questionId(), row.revision()));
            receipt(row, row.answerRevision() == null ? 0 : row.answerRevision(), state, hash, key);
        }
        private void receipt(Current row, long answerRevision, String state, String hash, String key) {
            jdbc.update("INSERT INTO answer_receipts VALUES(?,?,?,?,?,?,?)", key, hash,
                    row.packageId(), row.questionId(), row.revision() + 1, answerRevision, state);
        }
    }
}
