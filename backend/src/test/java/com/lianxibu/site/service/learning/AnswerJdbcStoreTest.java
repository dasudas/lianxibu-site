package com.lianxibu.site.service.learning;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.learning.JdbcLearningAnswers;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.Clock;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class AnswerJdbcStoreTest {
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private AnswerLifecycleService service(String actor) {
        return new AnswerLifecycleService(new SharedWriteExecutor(new JdbcSharedWriteEntry(source),new DataSourceTransactionManager(source)),
                new AnswerJdbcStore(new JdbcLearningAnswers(source),actor,Clock.systemUTC()));
    }
    private static ActionContext action(String key) { return new ActionContext(key,"fixture-answer",null); }
    @BeforeEach void setup() {
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:answer-store-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,state VARCHAR(16),revision BIGINT)");
        jdbc.execute("CREATE TABLE learning_items(package_id VARCHAR(64),kind VARCHAR(16),id VARCHAR(64),title VARCHAR(200),body_json CLOB,answer_text CLOB,answer_state VARCHAR(16),answer_generation_state VARCHAR(32),revision BIGINT,answer_revision BIGINT,answer_review_hash_v1 BINARY(32),PRIMARY KEY(package_id,kind,id))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        for(String pkg:new String[]{"fictional-a","fictional-b"}) {
            jdbc.update("INSERT INTO learning_packages VALUES(?,'published',1)",pkg);
            jdbc.update("INSERT INTO learning_items VALUES(?,'question','q','Fictional question','{}',NULL,NULL,'not_generated',1,NULL,NULL)",pkg);
        }
    }
    @Test void lifecycleIsDurableCompositeScopedAndRequiresCurrentReview() {
        var a=service("fictional-owner");
        var saved=a.edit("fictional-a","q",1,"Fictional\r\nanswer",action("edit"));
        assertEquals(1,saved.answerRevision());assertNull(a.publicAnswer("fictional-a","q"));
        assertTrue(service("fictional-owner").edit("fictional-a","q",1,"Fictional\r\nanswer",action("edit")).replayed());
        assertThrows(IllegalArgumentException.class,()->a.edit("fictional-a","q",1,"Changed",action("edit")));
        a.action("fictional-a","q",2,AnswerLifecycleService.Action.APPROVE,"Fictional reviewed",action("approve"));
        assertNull(a.publicAnswer("fictional-a","q"));
        a.action("fictional-a","q",3,AnswerLifecycleService.Action.PUBLISH,"Fictional publish",action("publish"));
        assertEquals("Fictional\nanswer",a.publicAnswer("fictional-a","q"));
        jdbc.update("UPDATE learning_items SET answer_text='Tampered' WHERE package_id='fictional-a'");
        assertNull(a.publicAnswer("fictional-a","q"));
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM learning_packages WHERE id='fictional-a'",Integer.class));
        assertEquals("{}",jdbc.queryForObject("SELECT body_json FROM learning_items WHERE package_id='fictional-a'",String.class));
        service("another-owner").edit("fictional-b","q",1,"Independent",action("edit"));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
        jdbc.update("UPDATE learning_packages SET state='withdrawn' WHERE id='fictional-a'");
        assertNull(a.publicAnswer("fictional-a","q"));
    }
    @Test void failedReceiptRollsBackAnswerAndStaleRevisionIsRejected() {
        jdbc.execute("ALTER TABLE request_receipts ADD CONSTRAINT reject_failure CHECK(request_key <> 'fail')");
        var a=service("fictional-owner");
        assertThrows(RuntimeException.class,()->a.edit("fictional-a","q",1,"Fictional",action("fail")));
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM learning_items WHERE package_id='fictional-a'",Integer.class));
        assertNull(jdbc.queryForObject("SELECT answer_text FROM learning_items WHERE package_id='fictional-a'",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
        assertThrows(AnswerLifecycleService.Rejected.class,()->a.edit("fictional-a","q",2,"Fictional",action("stale")));
    }
}
