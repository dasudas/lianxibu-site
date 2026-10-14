package com.lianxibu.site.repository.learning;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Published compatibility reads against the verified live composite-key learning tables. */
public final class JdbcLearningReads {
    public record PackageRow(String id, String title, String state, long revision) { }
    public record ItemRow(String packageId, String kind, String id, String title, int position,
                          String bodyJson, String answerText, String answerState,
                          String generationState, long revision) { }
    public record LinkRow(String fromKind, String fromId, String relation, String toKind,
                          String toId, int position) { }
    public record Page<T>(List<T> items, long total) { }

    private static final RowMapper<PackageRow> PACKAGE = (rs, index) ->
            new PackageRow(rs.getString("id"), rs.getString("title"), rs.getString("state"),
                    rs.getLong("revision"));
    private static final RowMapper<ItemRow> ITEM = (rs, index) ->
            new ItemRow(rs.getString("package_id"), rs.getString("kind"), rs.getString("id"),
                    rs.getString("title"), rs.getInt("position_no"), rs.getString("body_json"),
                    rs.getString("answer_text"), rs.getString("answer_state"),
                    rs.getString("answer_generation_state"), rs.getLong("revision"));
    private static final RowMapper<LinkRow> LINK = (rs, index) ->
            new LinkRow(rs.getString("from_kind"), rs.getString("from_id"),
                    rs.getString("relation_kind"), rs.getString("to_kind"),
                    rs.getString("to_id"), rs.getInt("position_no"));
    private static final String ITEM_COLUMNS = "i.package_id, i.kind, i.id, i.title, i.position_no, "
            + "i.body_json, CASE WHEN i.answer_state='published' THEN i.answer_text ELSE NULL END "
            + "AS answer_text, i.answer_state, i.answer_generation_state, i.revision";
    private final JdbcTemplate jdbc;

    public JdbcLearningReads(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("learning data source required");
        jdbc = new JdbcTemplate(dataSource);
    }

    public LearningRelations relations() { return new JdbcLearningRelations(jdbc); }

    public Page<PackageRow> packages(long page, int size) {
        long total = count("SELECT COUNT(*) FROM learning_packages WHERE state = 'published'", List.of());
        List<PackageRow> rows = jdbc.query("SELECT id, title, state, revision FROM learning_packages "
                        + "WHERE state = 'published' ORDER BY id LIMIT ? OFFSET ?", PACKAGE,
                size, offset(page, size));
        return new Page<>(rows, total);
    }

    public PackageRow packageById(String packageId) {
        List<PackageRow> rows = jdbc.query("SELECT id, title, state, revision FROM learning_packages "
                        + "WHERE id = ? AND state = 'published'", PACKAGE, packageId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public ItemRow byId(String packageId, String kind, String id) {
        List<ItemRow> rows = jdbc.query("SELECT " + ITEM_COLUMNS + " FROM learning_items i "
                        + "JOIN learning_packages p ON p.id = i.package_id AND p.state = 'published' "
                        + "WHERE i.package_id = ? AND i.kind = ? AND i.id = ?", ITEM,
                packageId, kind, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Page<ItemRow> items(String packageId, String kind, String routeId, String moduleId,
                               String nodeId, String text, String difficulty, String questionKind,
                               long page, int size) {
        StringBuilder where = new StringBuilder(" FROM learning_items i JOIN learning_packages p "
                + "ON p.id = i.package_id AND p.state = 'published' "
                + "WHERE i.package_id = ? AND i.kind = ?");
        List<Object> args = new ArrayList<>(List.of(packageId, kind));
        appendRelation(where, args, kind, "route", routeId);
        appendRelation(where, args, kind, "module", moduleId);
        appendRelation(where, args, kind, "node", nodeId);
        if (text != null && !text.isBlank()) {
            where.append(" AND LOWER(i.title) LIKE ? ESCAPE '!'");
            args.add("%" + text.toLowerCase(java.util.Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%");
        }
        if (difficulty != null && "question".equals(kind)) {
            where.append(" AND JSON_UNQUOTE(JSON_EXTRACT(i.body_json, '$.difficulty')) = ?");
            args.add(difficulty);
        }
        if (questionKind != null && "question".equals(kind)) {
            where.append(" AND JSON_UNQUOTE(JSON_EXTRACT(i.body_json, '$.question_kind')) = ?");
            args.add(questionKind);
        }
        long total = count("SELECT COUNT(*)" + where, args);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset(page, size));
        List<ItemRow> rows = jdbc.query("SELECT " + ITEM_COLUMNS + where
                        + " ORDER BY i.position_no, i.id LIMIT ? OFFSET ?", ITEM, pageArgs.toArray());
        return new Page<>(rows, total);
    }

    public List<LinkRow> outgoing(String packageId, String fromKind, String fromId,
                                  String relation, String toKind) {
        return jdbc.query("SELECT from_kind, from_id, relation_kind, to_kind, to_id, position_no "
                        + "FROM learning_links WHERE package_id = ? AND from_kind = ? AND from_id = ? "
                        + "AND relation_kind = ? AND to_kind = ? ORDER BY position_no, to_id",
                LINK, packageId, fromKind, fromId, relation, toKind);
    }

    public List<LinkRow> incoming(String packageId, String toKind, String toId,
                                  String relation, String fromKind) {
        return jdbc.query("SELECT from_kind, from_id, relation_kind, to_kind, to_id, position_no "
                        + "FROM learning_links WHERE package_id = ? AND to_kind = ? AND to_id = ? "
                        + "AND relation_kind = ? AND from_kind = ? ORDER BY position_no, from_id",
                LINK, packageId, toKind, toId, relation, fromKind);
    }

    public List<ItemRow> graphNodes(String packageId, String moduleId, String afterId, int size) {
        StringBuilder sql = new StringBuilder("SELECT " + ITEM_COLUMNS + " FROM learning_items i "
                + "JOIN learning_packages p ON p.id = i.package_id AND p.state = 'published' "
                + "WHERE i.package_id = ? AND i.kind = 'node'");
        List<Object> args = new ArrayList<>(List.of(packageId));
        if (moduleId != null) appendRelation(sql, args, "node", "module", moduleId);
        if (afterId != null) { sql.append(" AND i.id > ?"); args.add(afterId); }
        sql.append(" ORDER BY i.id LIMIT ?");
        args.add(size);
        return jdbc.query(sql.toString(), ITEM, args.toArray());
    }

    public List<String> resolvePackages(String kind, String id) {
        return jdbc.query("SELECT i.package_id FROM learning_items i JOIN learning_packages p "
                        + "ON p.id = i.package_id AND p.state = 'published' "
                        + "WHERE i.kind = ? AND i.id = ? ORDER BY i.package_id LIMIT 2",
                (rs, index) -> rs.getString(1), kind, id);
    }

    private long count(String sql, List<Object> args) {
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private static long offset(long page, int size) {
        if (page < 1 || size < 1 || size > 50 || page > Long.MAX_VALUE / size) {
            throw new IllegalArgumentException("invalid learning page");
        }
        return (page - 1) * size;
    }

    private static void appendRelation(StringBuilder where, List<Object> args, String targetKind,
                                       String parentKind, String parentId) {
        if (parentId == null) return;
        if (targetKind.equals(parentKind)) {
            where.append(" AND i.id = ?"); args.add(parentId); return;
        }
        if (targetKind.equals("route") && parentKind.equals("module")) {
            where.append(" AND EXISTS (SELECT 1 FROM learning_links rm WHERE rm.package_id=i.package_id "
                    + "AND rm.from_kind='route' AND rm.from_id=i.id AND rm.relation_kind='contains' "
                    + "AND rm.to_kind='module' AND rm.to_id=?)");
            args.add(parentId); return;
        }
        String terminalKind = targetKind;
        String terminalRelation = switch (targetKind) {
            case "module" -> "contains";
            case "node" -> "contains";
            case "question" -> "asks";
            case "project" -> "practices";
            default -> throw new IllegalArgumentException("invalid learning kind");
        };
        if (parentKind.equals("node")) {
            where.append(" AND EXISTS (SELECT 1 FROM learning_links nx WHERE nx.package_id=i.package_id "
                    + "AND nx.from_kind='node' AND nx.from_id=? AND nx.relation_kind='")
                    .append(terminalRelation).append("' AND nx.to_kind='")
                    .append(terminalKind).append("' AND nx.to_id=i.id)");
            args.add(parentId); return;
        }
        if (parentKind.equals("module") && targetKind.equals("node")) {
            where.append(" AND EXISTS (SELECT 1 FROM learning_links mn WHERE mn.package_id=i.package_id "
                    + "AND mn.from_kind='module' AND mn.from_id=? AND mn.relation_kind='contains' "
                    + "AND mn.to_kind='node' AND mn.to_id=i.id)");
            args.add(parentId); return;
        }
        if (parentKind.equals("route") && targetKind.equals("module")) {
            where.append(" AND EXISTS (SELECT 1 FROM learning_links rm WHERE rm.package_id=i.package_id "
                    + "AND rm.from_kind='route' AND rm.from_id=? AND rm.relation_kind='contains' "
                    + "AND rm.to_kind='module' AND rm.to_id=i.id)");
            args.add(parentId); return;
        }
        if (parentKind.equals("module") && SetLike.CHILD.contains(targetKind)) {
            where.append(" AND EXISTS (SELECT 1 FROM learning_links mn JOIN learning_links nx "
                    + "ON nx.package_id=mn.package_id AND nx.from_kind='node' AND nx.from_id=mn.to_id "
                    + "WHERE mn.package_id=i.package_id AND mn.from_kind='module' AND mn.from_id=? "
                    + "AND mn.relation_kind='contains' AND mn.to_kind='node' "
                    + "AND nx.relation_kind='").append(terminalRelation).append("' AND nx.to_kind='")
                    .append(terminalKind).append("' AND nx.to_id=i.id)");
            args.add(parentId); return;
        }
        if (parentKind.equals("route") && targetKind.equals("node")) {
            where.append(" AND EXISTS (SELECT 1 FROM learning_links rm JOIN learning_links mn "
                    + "ON mn.package_id=rm.package_id AND mn.from_kind='module' AND mn.from_id=rm.to_id "
                    + "WHERE rm.package_id=i.package_id AND rm.from_kind='route' AND rm.from_id=? "
                    + "AND rm.relation_kind='contains' AND rm.to_kind='module' "
                    + "AND mn.relation_kind='contains' AND mn.to_kind='node' AND mn.to_id=i.id)");
            args.add(parentId); return;
        }
        if (parentKind.equals("route") && SetLike.CHILD.contains(targetKind)) {
            where.append(" AND EXISTS (SELECT 1 FROM learning_links rm JOIN learning_links mn "
                    + "ON mn.package_id=rm.package_id AND mn.from_kind='module' AND mn.from_id=rm.to_id "
                    + "JOIN learning_links nx ON nx.package_id=mn.package_id AND nx.from_kind='node' "
                    + "AND nx.from_id=mn.to_id WHERE rm.package_id=i.package_id AND rm.from_kind='route' "
                    + "AND rm.from_id=? AND rm.relation_kind='contains' AND rm.to_kind='module' "
                    + "AND mn.relation_kind='contains' AND mn.to_kind='node' "
                    + "AND nx.relation_kind='").append(terminalRelation).append("' AND nx.to_kind='")
                    .append(terminalKind).append("' AND nx.to_id=i.id)");
            args.add(parentId); return;
        }
        throw new IllegalArgumentException("unsupported learning filter");
    }

    private static final class SetLike {
        static final java.util.Set<String> CHILD = java.util.Set.of("question", "project");
    }
}
