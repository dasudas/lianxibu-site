package com.lianxibu.site.service.learning;

import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Typed learning API port. Import items are frozen JSON values after boundary validation. */
public interface LearningService {
    record PackageSummary(String id, String title, String description, long revision, String state) { }
    record Page<T>(List<T> items, long page, int pageSize, long total) { }
    record Route(String packageId, String id, String title, List<String> moduleIds,
                 long completedNodeCount, long eligibleNodeCount, String completionRate) { }
    record Module(String packageId, String id, String title, List<String> routeIds) { }
    record Resource(String title, String url) { }
    record Node(String packageId, String id, String moduleId, String title, String scope,
                List<String> goals, List<String> completionCriteria, List<String> dependencies,
                List<Resource> resources, long revision) { }
    record Question(String packageId, String id, String title, String question, String difficulty,
                    String kind, List<String> assessmentPoints, String answerState,
                    String generationState, long revision, String answer) { }
    record Project(String packageId, String id, String title, String description, List<String> goals,
                   List<String> requirements, List<String> tasks, List<String> deliverables,
                   List<String> acceptanceCriteria, long revision) { }
    record GraphEdge(String fromId, String toId, String kind) { }
    record Graph(List<Node> nodes, List<GraphEdge> edges, String nextCursor) { }
    record ResolvedPackage(String packageId, String kind, String id) { }
    record Query(String routeId, String moduleId, String text, String difficulty, String kind,
                 String nodeId, long page, int pageSize) { }
    record ImportChunk(String importId, long partNo, long partCount, String partHash, List<JsonNode> items) { }
    record ImportFinalize(String importId, String title, String description, long partCount,
                          String manifestHash) { }
    sealed interface PackageCommand permits Stage, Commit { }
    record Stage(Long expectedRevision, ImportChunk chunk) implements PackageCommand { }
    record Commit(Long expectedRevision, ImportFinalize manifest) implements PackageCommand { }
    sealed interface PackageImportResult permits ImportProgress, PackageSummaryResult { }
    record ImportProgress(String importId, long acceptedPartNo, String partHash,
                          Instant expiresAt) implements PackageImportResult { }
    record PackageSummaryResult(PackageSummary summary) implements PackageImportResult { }
    record AnswerWrite(long expectedRevision, String answer) { }
    record AnswerAction(long expectedRevision, String action, String reason) { }
    record ResourceRef(String kind, String id, String packageId) { }
    record MissingInput(String kind, String id, String businessDate, String reason) { }
    record Coverage(String state, List<MissingInput> missingInputs, List<String> missingDates,
                    List<String> noUpdateDates) { }
    record ActionResult(String operationRef, ResourceRef resultResourceRef, long resultRevision,
                        Long generation, Instant cutoff, Coverage coverage, List<ResourceRef> affected,
                        String nextCursor) { }

    Page<PackageSummary> packages(long page, int pageSize);
    PackageSummary packageSummary(String packageId);
    Page<Route> routes(String packageId, Query query);
    Page<Module> modules(String packageId, Query query);
    Page<Node> nodes(String packageId, Query query);
    Page<Question> questions(String packageId, Query query);
    Page<Project> projects(String packageId, Query query);
    Node node(String packageId, String nodeId);
    Question question(String packageId, String questionId);
    Project project(String packageId, String projectId);
    Graph graph(String packageId, String moduleId, String cursor);
    ResolvedPackage resolve(String kind, String id);
    PackageImportResult createPackage(Actor actor, PackageCommand command,
                                      String idempotencyKey, String csrfToken, String origin);
    PackageImportResult writePackage(Actor actor, String packageId, PackageCommand command,
                                     String idempotencyKey, String csrfToken, String origin);
    ActionResult packageAction(Actor actor, String packageId, AnswerAction command,
                               String idempotencyKey, String csrfToken, String origin);
    ActionResult writeAnswer(Actor actor, String packageId, String questionId, AnswerWrite command,
                             String idempotencyKey, String csrfToken, String origin);
    ActionResult answerAction(Actor actor, String packageId, String questionId, AnswerAction command,
                              String idempotencyKey, String csrfToken, String origin);
}
