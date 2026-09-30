package com.lianxibu.site.service.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianxibu.site.repository.learning.JdbcLearningReads;
import com.lianxibu.site.service.identity.IdentityService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LearningReadServiceTest {
    private static final LearningService.Query FIRST = new LearningService.Query(
            null, null, null, null, null, null, 1, 20);

    @Test
    void readsLegacyCompositeKeysAndOrderedFieldsWithoutReimport() {
        Fixture fixture = new Fixture();
        var reads = fixture.reads;
        assertEquals(2, reads.packages(1, 20).total());
        assertEquals("", reads.packageSummary("fixture-a").description());
        assertEquals(List.of("fixture-module"), reads.routes("fixture-a", FIRST).items().getFirst().moduleIds());
        assertEquals(List.of("fixture-route"), reads.modules("fixture-a", FIRST).items().getFirst().routeIds());
        var node = reads.node("fixture-a", "shared-node");
        assertEquals("fixture-module", node.moduleId());
        assertEquals(List.of("first", "second"), node.goals());
        assertEquals(List.of("shared-node"), node.dependencies());
        assertEquals("https://fixture.example.invalid/resource", node.resources().getFirst().url());
        assertEquals("Alpha", reads.node("fixture-a", "shared-node").title());
        assertEquals("Beta", reads.node("fixture-b", "shared-node").title());
        assertEquals("fixture-a", reads.question("fixture-a", "shared-question").packageId());
        assertEquals("fixture-b", reads.question("fixture-b", "shared-question").packageId());
        assertEquals(List.of("do one", "do two"),
                reads.project("fixture-a", "fixture-project").tasks());
        assertEquals("shared-node", reads.graph("fixture-a", "fixture-module", null)
                .nodes().getFirst().id());
        assertEquals(1, reads.questions("fixture-a", FIRST).total());
        assertEquals(1, reads.projects("fixture-a", FIRST).total());
        assertEquals(1, reads.nodes("fixture-a", FIRST).total());
        var filtered = new LearningService.Query("fixture-route", "fixture-module", "Alpha",
                null, null, null, 1, 1);
        assertEquals(1, reads.nodes("fixture-a", filtered).total());
        assertEquals(1, reads.nodes("fixture-a", filtered).items().size());
        assertEquals(0, reads.nodes("fixture-a", new LearningService.Query("fixture-route",
                "fixture-module", "Alpha", null, null, null, 2, 1)).items().size());
        assertEquals(1, reads.routes("fixture-a", new LearningService.Query(null,
                "fixture-module", null, null, null, null, 1, 20)).total());
        assertEquals(1, reads.questions("fixture-a", new LearningService.Query("fixture-route",
                "fixture-module", null, null, null, "shared-node", 1, 20)).total());
        assertEquals(1, reads.projects("fixture-a", new LearningService.Query("fixture-route",
                "fixture-module", null, null, null, "shared-node", 1, 20)).total());
        assertEquals(0L, fixture.count("request_receipts"));
    }

    @Test
    void hidesDraftPackagesAndUnpublishedAnswersAndRejectsAmbiguousLegacyLinks() {
        Fixture fixture = new Fixture();
        var reads = fixture.reads;
        rejected(IdentityService.Refusal.NOT_FOUND,
                () -> reads.packageSummary("fixture-draft"));
        rejected(IdentityService.Refusal.NOT_FOUND,
                () -> reads.node("fixture-draft", "hidden-node"));
        rejected(IdentityService.Refusal.NOT_FOUND,
                () -> reads.nodes("fixture-draft", FIRST));
        assertFalse(reads.packages(1, 20).items().stream()
                .anyMatch(item -> item.id().equals("fixture-draft")));
        assertNull(reads.question("fixture-a", "shared-question").answer());
        assertEquals("published answer", reads.question("fixture-b", "shared-question").answer());
        rejected(IdentityService.Refusal.IDENTITY_CONFLICT,
                () -> reads.resolve("node", "shared-node"));
        assertEquals("fixture-a", reads.resolve("project", "fixture-project").packageId());
        rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,
                () -> reads.createPackage(new IdentityService.Actor("fixture-owner", "owner"),
                        null, "fixture-key", "fixture-csrf", "https://site.example.invalid"));
    }

    private static void rejected(IdentityService.Refusal refusal, Runnable work) {
        assertEquals(refusal, assertThrows(IdentityService.Rejected.class, work::run).refusal());
    }

    private static final class Fixture {
        final DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:l01-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        final JdbcTemplate jdbc = new JdbcTemplate(source);
        final LearningReadService reads;

        Fixture() {
            jdbc.execute("CREATE TABLE learning_packages (id VARCHAR(64) PRIMARY KEY, title VARCHAR(200), "
                    + "state VARCHAR(16), revision BIGINT)");
            jdbc.execute("CREATE TABLE learning_items (package_id VARCHAR(64), kind VARCHAR(16), "
                    + "id VARCHAR(64), title VARCHAR(200), position_no INT, body_json VARCHAR(10000), "
                    + "answer_text VARCHAR(10000), answer_state VARCHAR(16), "
                    + "answer_generation_state VARCHAR(32), revision BIGINT, "
                    + "PRIMARY KEY (package_id, kind, id))");
            jdbc.execute("CREATE TABLE learning_links (package_id VARCHAR(64), from_kind VARCHAR(16), "
                    + "from_id VARCHAR(64), relation_kind VARCHAR(24), to_kind VARCHAR(16), "
                    + "to_id VARCHAR(64), position_no INT)");
            jdbc.execute("CREATE TABLE request_receipts (id VARCHAR(64))");
            pack("fixture-a", "published");
            pack("fixture-b", "published");
            pack("fixture-draft", "draft");
            hierarchy("fixture-a", "Alpha", "needs_review", "private answer");
            hierarchy("fixture-b", "Beta", "published", "published answer");
            item("fixture-draft", "node", "hidden-node", "Hidden", "{}", null, null);
            reads = new LearningReadService(new JdbcLearningReads(source));
        }

        void pack(String id, String state) {
            jdbc.update("INSERT INTO learning_packages VALUES (?, ?, ?, 1)", id, id, state);
        }

        void hierarchy(String pack, String nodeTitle, String answerState, String answer) {
            item(pack, "route", "fixture-route", "Route", "{}", null, null);
            item(pack, "module", "fixture-module", "Module", "{}", null, null);
            item(pack, "node", "shared-node", nodeTitle,
                    "{\"scope\":\"fixture\",\"goals_raw\":[{\"position\":2,\"value\":\"second\"},"
                            + "{\"position\":1,\"value\":\"first\"}],\"completion_criteria_raw\":[]}",
                    null, null);
            item(pack, "resource", "fixture-resource", "Resource",
                    "{\"url\":\"https://fixture.example.invalid/resource\"}", null, null);
            item(pack, "question", "shared-question", "Question",
                    "{\"question\":\"Question?\",\"difficulty\":\"beginner\","
                            + "\"question_kind\":\"concept\",\"assessment_points_raw\":[]}",
                    answer, answerState);
            if (pack.equals("fixture-a")) item(pack, "project", "fixture-project", "Project",
                    "{\"description\":\"Fixture\",\"tasks_raw\":[{\"position\":1,\"value\":\"do one\"},"
                            + "{\"position\":2,\"value\":\"do two\"}]}", null, null);
            link(pack, "route", "fixture-route", "contains", "module", "fixture-module");
            link(pack, "module", "fixture-module", "contains", "node", "shared-node");
            link(pack, "node", "shared-node", "requires", "node", "shared-node");
            link(pack, "node", "shared-node", "references", "resource", "fixture-resource");
            link(pack, "node", "shared-node", "asks", "question", "shared-question");
            if (pack.equals("fixture-a")) link(pack, "node", "shared-node", "practices",
                    "project", "fixture-project");
        }

        void item(String pack, String kind, String id, String title, String body,
                  String answer, String answerState) {
            jdbc.update("INSERT INTO learning_items VALUES (?, ?, ?, ?, 1, ?, ?, ?, 'not_generated', 1)",
                    pack, kind, id, title, body, answer, answerState);
        }

        void link(String pack, String fromKind, String fromId, String relation,
                  String toKind, String toId) {
            jdbc.update("INSERT INTO learning_links VALUES (?, ?, ?, ?, ?, ?, 1)",
                    pack, fromKind, fromId, relation, toKind, toId);
        }

        long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    }
}
