package com.lianxibu.site.service.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.learning.JdbcLearningReads;
import com.lianxibu.site.repository.learning.JdbcLearningReads.ItemRow;
import com.lianxibu.site.repository.learning.JdbcLearningReads.LinkRow;
import com.lianxibu.site.service.identity.IdentityService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Zero-reimport reader for current learning rows. Writes remain closed until B20/B21. */
public final class LearningReadService implements LearningService {
    private final JdbcLearningReads store;

    public LearningReadService(JdbcLearningReads store) {
        if (store == null) throw new IllegalArgumentException("learning reads required");
        this.store = store;
    }

    @Override
    public Page<PackageSummary> packages(long page, int pageSize) {
        var result = store.packages(page, pageSize);
        return new Page<>(result.items().stream().map(LearningReadService::summary).toList(),
                page, pageSize, result.total());
    }

    @Override
    public PackageSummary packageSummary(String packageId) {
        var row = store.packageById(id(packageId));
        if (row == null) throw notFound();
        return summary(row);
    }

    @Override
    public Page<Route> routes(String packageId, Query query) {
        var result = items(packageId, "route", query);
        return mapped(result, query, result.items().stream().map(this::route).toList());
    }

    @Override
    public Page<Module> modules(String packageId, Query query) {
        var result = items(packageId, "module", query);
        return mapped(result, query, result.items().stream().map(this::module).toList());
    }

    @Override
    public Page<Node> nodes(String packageId, Query query) {
        var result = items(packageId, "node", query);
        return mapped(result, query, result.items().stream().map(this::node).toList());
    }

    @Override
    public Page<Question> questions(String packageId, Query query) {
        var result = items(packageId, "question", query);
        return mapped(result, query, result.items().stream().map(this::question).toList());
    }

    @Override
    public Page<Project> projects(String packageId, Query query) {
        var result = items(packageId, "project", query);
        return mapped(result, query, result.items().stream().map(this::project).toList());
    }

    @Override
    public Node node(String packageId, String nodeId) {
        return node(required(packageId, "node", nodeId));
    }

    @Override
    public Question question(String packageId, String questionId) {
        return question(required(packageId, "question", questionId));
    }

    @Override
    public Project project(String packageId, String projectId) {
        return project(required(packageId, "project", projectId));
    }

    @Override
    public Graph graph(String packageId, String moduleId, String cursor) {
        String packageKey = id(packageId);
        if (store.packageById(packageKey) == null) throw notFound();
        String moduleKey = moduleId == null ? null : id(moduleId);
        if (moduleKey != null && store.byId(packageKey, "module", moduleKey) == null) throw notFound();
        String after = cursor == null ? null : id(cursor);
        List<ItemRow> rows = store.graphNodes(packageKey, moduleKey, after, 51);
        boolean hasNext = rows.size() > 50;
        if (hasNext) rows = rows.subList(0, 50);
        List<Node> nodes = rows.stream().map(this::node).toList();
        List<GraphEdge> edges = new ArrayList<>();
        for (ItemRow row : rows) {
            for (LinkRow link : store.outgoing(packageKey, "node", row.id(), "requires", "node")) {
                edges.add(new GraphEdge(row.id(), link.toId(), "prerequisite"));
            }
        }
        return new Graph(nodes, List.copyOf(edges), hasNext ? rows.getLast().id() : null);
    }

    @Override
    public ResolvedPackage resolve(String kind, String itemId) {
        if (!Set.of("route", "node", "question", "project").contains(kind)) throw notFound();
        List<String> packages = store.resolvePackages(kind, id(itemId));
        if (packages.isEmpty()) throw notFound();
        if (packages.size() > 1) throw new IdentityService.Rejected(
                IdentityService.Refusal.IDENTITY_CONFLICT, null);
        return new ResolvedPackage(packages.getFirst(), kind, itemId);
    }

    private JdbcLearningReads.Page<ItemRow> items(String packageId, String kind, Query query) {
        if (query == null) throw new IllegalArgumentException("learning query required");
        String packageKey = id(packageId);
        if (store.packageById(packageKey) == null) throw notFound();
        return store.items(packageKey, kind, query.routeId(), query.moduleId(), query.nodeId(),
                query.text(), query.difficulty(), query.kind(), query.page(), query.pageSize());
    }

    private ItemRow required(String packageId, String kind, String itemId) {
        ItemRow row = store.byId(id(packageId), kind, id(itemId));
        if (row == null) throw notFound();
        return row;
    }

    private Route route(ItemRow row) {
        List<String> modules = targets(store.outgoing(row.packageId(), "route", row.id(),
                "contains", "module"));
        Set<String> eligible = new HashSet<>();
        for (String module : modules) {
            eligible.addAll(targets(store.outgoing(row.packageId(), "module", module,
                    "contains", "node")));
        }
        // This public reader has no user identity; private progress is added by a later provider.
        return new Route(row.packageId(), row.id(), row.title(), modules, 0, eligible.size(), "0");
    }

    private Module module(ItemRow row) {
        List<String> routes = store.incoming(row.packageId(), "module", row.id(),
                "contains", "route").stream().map(LinkRow::fromId).toList();
        return new Module(row.packageId(), row.id(), row.title(), routes);
    }

    private Node node(ItemRow row) {
        JsonNode body = body(row);
        List<String> modules = store.incoming(row.packageId(), "node", row.id(),
                "contains", "module").stream().map(LinkRow::fromId).distinct().toList();
        if (modules.size() != 1) throw unavailable();
        List<String> dependencies = targets(store.outgoing(row.packageId(), "node", row.id(),
                "requires", "node"));
        List<Resource> resources = resources(row.packageId(), "node", row.id());
        return new Node(row.packageId(), row.id(), modules.getFirst(), row.title(),
                text(body, "scope"), ordered(body, "goals_raw"),
                ordered(body, "completion_criteria_raw"), dependencies, resources, row.revision());
    }

    private Question question(ItemRow row) {
        JsonNode body = body(row);
        String state = row.answerState();
        if(state==null||!Set.of("draft","approved","published","withdrawn").contains(state))throw unavailable();
        String generation=row.generationState();
        if(generation==null||!Set.of("not_generated","generated","reviewed","needs_revision").contains(generation))throw unavailable();
        String answer = "published".equals(state) ? row.answerText() : null;
        if ("published".equals(state) && answer == null) throw unavailable();
        return new Question(row.packageId(), row.id(), row.title(), text(body, "question"),
                text(body, "difficulty"), text(body, "question_kind"),
                ordered(body, "assessment_points_raw"), state,
                generation,
                row.revision(), answer);
    }

    private Project project(ItemRow row) {
        JsonNode body = body(row);
        return new Project(row.packageId(), row.id(), row.title(), text(body, "description"),
                ordered(body, "goals_raw"), ordered(body, "requirements_raw"),
                ordered(body, "tasks_raw"), ordered(body, "deliverables_raw"),
                ordered(body, "acceptance_criteria_raw"), row.revision());
    }

    private List<Resource> resources(String packageId, String kind, String itemId) {
        List<Resource> values = new ArrayList<>();
        for (LinkRow link : store.outgoing(packageId, kind, itemId, "references", "resource")) {
            ItemRow resource = store.byId(packageId, "resource", link.toId());
            if (resource == null) throw unavailable();
            values.add(new Resource(resource.title(), text(body(resource), "url")));
        }
        return List.copyOf(values);
    }

    private static JsonNode body(ItemRow row) {
        JsonNode body = CanonicalJson.parse(row.bodyJson());
        if (!body.isObject()) throw unavailable();
        return body;
    }

    /** Old body_json stores each list element as {position,value}; preserve its explicit order. */
    private static List<String> ordered(JsonNode body, String key) {
        JsonNode array = body.get(key);
        if (array == null || array.isNull()) return List.of();
        if (!array.isArray()) throw unavailable();
        record Ordered(int position, String value) { }
        List<Ordered> values = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int fallback = 0;
        for (JsonNode item : array) {
            if (item.isTextual()) values.add(new Ordered(fallback, item.textValue()));
            else if (item.isObject() && item.path("position").isIntegralNumber()
                    && item.path("value").isTextual()) {
                values.add(new Ordered(item.get("position").intValue(), item.get("value").textValue()));
            } else throw unavailable();
            fallback++;
        }
        for (Ordered value : values) if (!seen.add(value.position())) throw unavailable();
        values.sort(Comparator.comparingInt(Ordered::position));
        return values.stream().map(Ordered::value).toList();
    }

    private static String text(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw unavailable();
        return value.textValue();
    }

    private static List<String> targets(List<LinkRow> links) {
        return links.stream().map(LinkRow::toId).toList();
    }

    private static PackageSummary summary(JdbcLearningReads.PackageRow row) {
        // Current physical package table has no description column; no legacy prose is invented.
        return new PackageSummary(row.id(), row.title(), "", row.revision(), row.state());
    }

    private static <T> Page<T> mapped(JdbcLearningReads.Page<?> result, Query query, List<T> items) {
        return new Page<>(items, query.page(), query.pageSize(), result.total());
    }

    private static String id(String value) { return WireValues.id(value, false); }

    private static IdentityService.Rejected notFound() {
        return new IdentityService.Rejected(IdentityService.Refusal.NOT_FOUND, null);
    }

    private static IdentityService.Rejected unavailable() {
        return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE, "1");
    }

    @Override public PackageImportResult createPackage(IdentityService.Actor actor, PackageCommand command,
            String key, String csrf, String origin) { throw unavailable(); }
    @Override public PackageImportResult writePackage(IdentityService.Actor actor, String packageId,
            PackageCommand command, String key, String csrf, String origin) { throw unavailable(); }
    @Override public ActionResult packageAction(IdentityService.Actor actor, String packageId,
            AnswerAction command, String key, String csrf, String origin) { throw unavailable(); }
    @Override public ActionResult writeAnswer(IdentityService.Actor actor, String packageId, String questionId,
            AnswerWrite command, String key, String csrf, String origin) { throw unavailable(); }
    @Override public ActionResult answerAction(IdentityService.Actor actor, String packageId, String questionId,
            AnswerAction command, String key, String csrf, String origin) { throw unavailable(); }
}
