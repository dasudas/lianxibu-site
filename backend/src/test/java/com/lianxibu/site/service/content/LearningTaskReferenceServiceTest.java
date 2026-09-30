package com.lianxibu.site.service.content;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.learning.JdbcLearningReads;
import com.lianxibu.site.service.content.ContentService.LearningTask;
import com.lianxibu.site.service.learning.LearningReadService;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class LearningTaskReferenceServiceTest {
    private JdbcTemplate jdbc;
    private LearningTaskReferenceService references;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:task-ref-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,state VARCHAR(16))");
        jdbc.execute("CREATE TABLE learning_items(package_id VARCHAR(64),kind VARCHAR(16),id VARCHAR(64),"
                + "title VARCHAR(200),position_no INT,body_json VARCHAR(100),answer_text VARCHAR(100),"
                + "answer_state VARCHAR(16),answer_generation_state VARCHAR(32),revision BIGINT,"
                + "PRIMARY KEY(package_id,kind,id))");
        jdbc.execute("CREATE TABLE learning_links(package_id VARCHAR(64),from_kind VARCHAR(16),"
                + "from_id VARCHAR(64),relation_kind VARCHAR(24),to_kind VARCHAR(16),"
                + "to_id VARCHAR(64),position_no INT)");
        jdbc.update("INSERT INTO learning_packages VALUES('pack-a','published'),('pack-b','published')");
        jdbc.update("INSERT INTO learning_items VALUES('pack-a','node','shared','Node A',1,'{}',null,null,null,1)");
        jdbc.update("INSERT INTO learning_items VALUES('pack-b','node','shared','Node B',1,'{}',null,null,null,1)");
        jdbc.update("INSERT INTO learning_links VALUES('pack-a','module','module',"
                + "'contains','node','shared',1),('pack-b','module','module',"
                + "'contains','node','shared',1)");
        references = new LearningTaskReferenceService(new LearningReadService(new JdbcLearningReads(source)),
                new DataSourceTransactionManager(source));
    }

    @Test void noCandidateIsNullAndMultipleCandidatesAreRejected() {
        assertNull(references.prepare("daily", List.of()));
        assertThrows(LearningTaskReferenceService.Rejected.class,
                () -> references.prepare("daily", List.of(task("pack-a"), task("pack-b"))));
        assertThrows(LearningTaskReferenceService.Rejected.class,
                () -> references.prepare("weekly", List.of(task("pack-a"))));
    }

    @Test void fullPackageKeySelectsTheCorrectPublishedObjectAndServerTitle() {
        LearningTask a = references.prepare("daily", List.of(task("pack-a")));
        LearningTask b = references.prepare("daily", List.of(task("pack-b")));
        assertEquals("Node A", a.title());
        assertEquals("Node B", b.title());
        assertEquals("pack-a", references.linkStatus(a).link().packageId());
        assertEquals("pack-b", references.linkStatus(b).link().packageId());
    }

    @Test void withdrawalMakesLinkUnavailableWithoutTouchingReportOrPersonalRecords() {
        LearningTask frozen = references.prepare("daily", List.of(task("pack-a")));
        jdbc.execute("CREATE TABLE contents(id VARCHAR(64),body VARCHAR(128),state VARCHAR(16))");
        jdbc.execute("CREATE TABLE user_records(id VARCHAR(64),status VARCHAR(16))");
        jdbc.update("INSERT INTO contents VALUES('report','old public body','published')");
        jdbc.update("INSERT INTO user_records VALUES('record','learning')");
        jdbc.update("UPDATE learning_packages SET state='withdrawn' WHERE id='pack-a'");
        var status = references.linkStatus(frozen);
        assertFalse(status.available());
        assertNull(status.link());
        assertEquals("learning_unavailable", status.message());
        assertEquals("old public body", jdbc.queryForObject("SELECT body FROM contents", String.class));
        assertEquals("learning", jdbc.queryForObject("SELECT status FROM user_records", String.class));
        assertEquals("Node A", frozen.title());
    }

    @Test void unavailableAndWrongKindsCannotEnterDailyReport() {
        jdbc.update("UPDATE learning_packages SET state='draft' WHERE id='pack-a'");
        assertThrows(LearningTaskReferenceService.Rejected.class,
                () -> references.prepare("daily", List.of(task("pack-a"))));
        assertThrows(LearningTaskReferenceService.Rejected.class,
                () -> references.prepare("daily", List.of(new LearningTask(
                        "pack-b", "route", "shared", "x", "reason"))));
    }

    private static LearningTask task(String packageId) {
        return new LearningTask(packageId, "node", "shared", "untrusted title", "useful reason");
    }
}
