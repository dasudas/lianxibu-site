package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Typed persistence for the five jobs record kinds; only logical roots own call budget. */
public final class JdbcJobRecords {
    public record Row(String id, String kind, String state, long revision, int callCount,
                      Integer maxAttempts, String rootId, Integer attemptNo,
                      String logicalKey, Long generation, Long stagePart,
                      String requestJson, String payloadJson) { }
    public record Intent(String id, String logicalKey, long generation, String taskType,
                         LocalDate businessDate) { }
    public record Root(String id, String intentId, String logicalKey, long generation,
                       long stagePart, String taskType, int priority) { }
    public record Attempt(String id, String rootId, int attemptNo, String requestJson,
                          String snapshotOwnerId, String inputHash) { }
    public record Part(String id, String ownerId, String purpose, String stampId,
                       long partNo, String payloadJson, String payloadHash) { }
    public record Disposition(String id, String actionKey, String targetType,
                              String targetKeyJson, String operatorId, String reason,
                              String payloadJson) { }
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("job record identity or budget conflict"); }
    }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PURPOSE = Set.of("input", "review", "dependency", "result");
    private final JdbcTemplate jdbc;

    public JdbcJobRecords(DataSource source) {
        if (source == null) throw new IllegalArgumentException("job data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Row byId(String id) {
        List<Row> rows = jdbc.query("SELECT id,record_kind,state,revision,call_count,max_attempts,"
                        + "root_job_id,attempt_no,logical_key,generation,stage_part,request_json,payload_json"
                        + " FROM jobs WHERE id=?", (rs, ignored) -> new Row(rs.getString(1),
                        rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5),
                        number(rs.getObject(6)), rs.getString(7), number(rs.getObject(8)),
                        rs.getString(9), longNumber(rs.getObject(10)), longNumber(rs.getObject(11)),
                        rs.getString(12), rs.getString(13)), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public String ensureIntent(Intent intent, Instant now) {
        requireTx();
        if (intent == null || !id(intent.id()) || !text(intent.logicalKey(), 4096)
                || !id(intent.taskType()) || intent.businessDate() == null
                || intent.generation() < 0 || intent.generation() > CanonicalJson.MAX_SAFE_INTEGER)
            throw new Conflict();
        String natural = intent.logicalKey() + "\u0000" + intent.generation();
        byte[] identity = identity("generation_intent", natural);
        Row previous = byIdentity(identity);
        if (previous != null) {
            if (!"generation_intent".equals(previous.kind())
                    || !intent.logicalKey().equals(previous.logicalKey())
                    || previous.generation() == null || previous.generation() != intent.generation()
                    || !sameIntent(previous.id(), intent)) throw new Conflict();
            return previous.id();
        }
        insertRecord(intent.id(), "generation_intent", identity, "waiting_input", 0, now,
                "logical_key,logical_key_hash,generation,task_type,business_date,input_state",
                intent.logicalKey(), sha(intent.logicalKey()), intent.generation(),
                intent.taskType(), java.sql.Date.valueOf(intent.businessDate()), "waiting_input");
        return intent.id();
    }

    public String createRoot(Root root, Instant now) {
        requireTx();
        if (root == null || !id(root.id()) || !id(root.intentId()) || !text(root.logicalKey(), 4096)
                || root.generation() < 0 || root.stagePart() < 0
                || root.generation() > CanonicalJson.MAX_SAFE_INTEGER
                || root.stagePart() > CanonicalJson.MAX_SAFE_INTEGER || root.priority() < 1
                || root.priority() > 14 || !id(root.taskType())) throw new Conflict();
        Row parent = byId(root.intentId());
        if (parent == null || !"generation_intent".equals(parent.kind())
                || parent.generation() == null || parent.generation() != root.generation()
                || !root.logicalKey().equals(parent.logicalKey())) throw new Conflict();
        String natural = root.logicalKey() + "\u0000" + root.generation() + "\u0000" + root.stagePart();
        byte[] identity = identity("logical_task", natural);
        Row previous = byIdentity(identity);
        if (previous != null) {
            if (!"logical_task".equals(previous.kind()) || !root.logicalKey().equals(previous.logicalKey())
                    || previous.generation() == null || previous.generation() != root.generation()
                    || previous.stagePart() == null || previous.stagePart() != root.stagePart()
                    || !sameRoot(previous.id(), root)) throw new Conflict();
            return previous.id();
        }
        insertRecord(root.id(), "logical_task", identity, "waiting_input", root.priority(), now,
                "logical_key,logical_key_hash,generation,stage_part,intent_id,task_type,max_attempts",
                root.logicalKey(), sha(root.logicalKey()), root.generation(), root.stagePart(),
                root.intentId(), root.taskType(), 3);
        return root.id();
    }

    public String prepareAttempt(Attempt attempt, Instant now) {
        requireTx();
        if (attempt == null || !id(attempt.id()) || !id(attempt.rootId()) || !id(attempt.snapshotOwnerId())
                || attempt.attemptNo() < 1 || attempt.attemptNo() > 3
                || !hash(attempt.inputHash()) || !jsonObject(attempt.requestJson())) throw new Conflict();
        Row root = byId(attempt.rootId());
        if (root == null || !"logical_task".equals(root.kind()) || root.callCount() >= 3
                || attempt.attemptNo() != root.callCount() + 1) throw new Conflict();
        Row owner = byId(attempt.snapshotOwnerId());
        if (owner == null || !Set.of("logical_task", "generation_intent").contains(owner.kind())) throw new Conflict();
        if (!attempt.snapshotOwnerId().equals(attempt.rootId())
                && !jdbc.queryForObject("SELECT intent_id=? FROM jobs WHERE id=?", Boolean.class,
                    attempt.snapshotOwnerId(), attempt.rootId())) throw new Conflict();
        byte[] identity = identity("ai_attempt", attempt.rootId() + "\u0000" + attempt.attemptNo());
        Row previous = byIdentity(identity);
        if (previous != null) {
            if (!"ai_attempt".equals(previous.kind()) || !attempt.rootId().equals(previous.rootId())
                    || previous.attemptNo() == null || previous.attemptNo() != attempt.attemptNo()
                    || !sameJson(previous.requestJson(), attempt.requestJson())
                    || !sameAttempt(previous.id(), attempt)) throw new Conflict();
            return previous.id();
        }
        insertRecord(attempt.id(), "ai_attempt", identity, "prepared", 0, now,
                "root_job_id,attempt_no,request_json,snapshot_owner_id,input_hash",
                attempt.rootId(), attempt.attemptNo(), attempt.requestJson(),
                attempt.snapshotOwnerId(), HexFormat.of().parseHex(attempt.inputHash()));
        return attempt.id();
    }

    /** Budget reservation is separate from preparing the immutable request. */
    public int reserveCall(String rootId, String attemptId, long expectedRevision, Instant now) {
        requireTx();
        Row root = byId(rootId);
        Row attempt = byId(attemptId);
        if (root == null || attempt == null || !"logical_task".equals(root.kind())
                || !"ai_attempt".equals(attempt.kind()) || !rootId.equals(attempt.rootId())
                || !"prepared".equals(attempt.state()) || !"queued".equals(root.state())
                || root.revision() != expectedRevision
                || root.callCount() >= 3 || attempt.attemptNo() == null
                || attempt.attemptNo() != root.callCount() + 1) throw new Conflict();
        if (jdbc.update("UPDATE jobs SET call_count=call_count+1,revision=revision+1,"
                        + "state='running',updated_at=? WHERE id=? AND revision=? AND call_count<3",
                Timestamp.from(now), rootId, expectedRevision) != 1) throw new Conflict();
        if (jdbc.update("UPDATE jobs SET state='running',revision=revision+1,updated_at=?"
                        + " WHERE id=? AND state='prepared'", Timestamp.from(now), attemptId) != 1) throw new Conflict();
        return root.callCount() + 1;
    }

    public String putPart(Part part, Instant now) {
        requireTx();
        if (part == null || !id(part.id()) || !id(part.ownerId()) || !PURPOSE.contains(part.purpose())
                || !id(part.stampId()) || part.partNo() < 0
                || part.partNo() > CanonicalJson.MAX_SAFE_INTEGER || !hash(part.payloadHash())
                || !json(part.payloadJson())) throw new Conflict();
        Row owner = byId(part.ownerId());
        if (owner == null || !Set.of("logical_task", "generation_intent").contains(owner.kind())) throw new Conflict();
        String natural = part.ownerId() + "\u0000" + part.purpose() + "\u0000" + part.stampId()
                + "\u0000" + part.partNo();
        byte[] identity = identity("snapshot_part", natural);
        Row previous = byIdentity(identity);
        if (previous != null) {
            if (!"snapshot_part".equals(previous.kind())
                    || !sameJson(previous.payloadJson(), part.payloadJson())
                    || !samePart(previous.id(), part)) throw new Conflict();
            return previous.id();
        }
        insertRecord(part.id(), "snapshot_part", identity, "frozen", 0, now,
                "owner_job_id,purpose,stamp_id,part_no,payload_json,payload_hash",
                part.ownerId(), part.purpose(), part.stampId(), part.partNo(),
                part.payloadJson(), HexFormat.of().parseHex(part.payloadHash()));
        return part.id();
    }

    public String appendDisposition(Disposition value, Instant now) {
        requireTx();
        if (value == null || !id(value.id()) || value.actionKey() == null
                || !value.actionKey().matches("[A-Za-z0-9._:-]{1,128}") || !id(value.targetType())
                || !jsonObject(value.targetKeyJson()) || !id(value.operatorId())
                || !text(value.reason(), 2048) || !jsonObject(value.payloadJson())) throw new Conflict();
        byte[] actionHash = sha(value.actionKey());
        byte[] identity = identity("disposition", value.actionKey());
        Row previous = byIdentity(identity);
        if (previous != null) {
            if (!"disposition".equals(previous.kind()) || !sameDisposition(previous.id(), value))
                throw new Conflict();
            return previous.id();
        }
        insertRecord(value.id(), "disposition", identity, "applied", 0, now,
                "action_key_hash,target_type,target_key_json,target_key_hash,operator_id,reason,"
                        + "acted_at,action_payload,action_payload_hash",
                actionHash, value.targetType(),
                value.targetKeyJson(), sha(canonical(value.targetKeyJson())), value.operatorId(),
                value.reason(), Timestamp.from(now), value.payloadJson(),
                sha(canonical(value.payloadJson())));
        return value.id();
    }

    public boolean modelSendable(String id) {
        Row row = byId(id);
        if (row == null || !"ai_attempt".equals(row.kind()) || !"running".equals(row.state())
                || row.rootId() == null || row.requestJson() == null || row.attemptNo() == null)
            return false;
        Row root = byId(row.rootId());
        return root != null && "logical_task".equals(root.kind()) && "running".equals(root.state())
                && root.callCount() >= row.attemptNo();
    }

    private boolean sameIntent(String id, Intent value) {
        return jdbc.queryForObject("SELECT task_type=? AND business_date=? FROM jobs WHERE id=?",
                Boolean.class, value.taskType(), java.sql.Date.valueOf(value.businessDate()), id);
    }
    private boolean sameRoot(String id, Root value) {
        return jdbc.queryForObject("SELECT intent_id=? AND task_type=? AND priority_no=?"
                        + " FROM jobs WHERE id=?", Boolean.class,
                value.intentId(), value.taskType(), value.priority(), id);
    }
    private boolean sameAttempt(String id, Attempt value) {
        List<Object[]> rows = jdbc.query("SELECT snapshot_owner_id,input_hash FROM jobs WHERE id=?",
                (rs, ignored) -> new Object[]{rs.getString(1), rs.getBytes(2)}, id);
        return rows.size() == 1 && value.snapshotOwnerId().equals(rows.getFirst()[0])
                && MessageDigest.isEqual((byte[]) rows.getFirst()[1],
                    HexFormat.of().parseHex(value.inputHash()));
    }
    private boolean samePart(String id, Part value) {
        byte[] old = jdbc.queryForObject("SELECT payload_hash FROM jobs WHERE id=?", byte[].class, id);
        return old != null && MessageDigest.isEqual(old, HexFormat.of().parseHex(value.payloadHash()));
    }
    private boolean sameDisposition(String id, Disposition value) {
        List<Object[]> rows = jdbc.query("SELECT target_type,target_key_json,operator_id,reason,"
                        + "action_payload FROM jobs WHERE id=?", (rs, ignored) -> new Object[]{
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)}, id);
        if (rows.size() != 1) return false;
        Object[] old = rows.getFirst();
        return value.targetType().equals(old[0]) && sameJson((String) old[1], value.targetKeyJson())
                && value.operatorId().equals(old[2]) && value.reason().equals(old[3])
                && sameJson((String) old[4], value.payloadJson());
    }

    private Row byIdentity(byte[] hash) {
        List<String> ids = jdbc.query("SELECT id FROM jobs WHERE record_identity_hash=?",
                (rs, ignored) -> rs.getString(1), hash);
        return ids.isEmpty() ? null : byId(ids.getFirst());
    }
    private void insertRecord(String id, String kind, byte[] identity, String state,
                              int priority, Instant now, String extraColumns, Object... extras) {
        String key = kind + ":" + HexFormat.of().formatHex(identity);
        int extraCount = extraColumns.split(",").length;
        if (extraCount != extras.length) throw new IllegalArgumentException("typed job columns mismatch");
        String placeholders = String.join(",", Collections.nCopies(extraCount, "?"));
        List<Object> values = new ArrayList<>();
        Collections.addAll(values, id, key, kind, id, state, priority, Timestamp.from(now),
                0, Timestamp.from(now), Timestamp.from(now), kind, identity, 0);
        Collections.addAll(values, extras);
        jdbc.update("INSERT INTO jobs(id,business_key,task_kind,target_id,state,priority_no,"
                        + "eligible_at,revision,created_at,updated_at,record_kind,record_identity_hash,"
                        + "call_count," + extraColumns + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,"
                        + placeholders + ")", values.toArray());
    }
    private static void requireTx() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("job mutation requires the shared write transaction");
    }
    private static byte[] identity(String kind, String key) {
        ObjectNode value = JSON.createObjectNode();
        value.put("record_kind", kind);
        value.put("natural_key", key);
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.IDENTITY, value));
    }
    private static boolean sameJson(String a, String b) {
        return a != null && b != null && canonical(a).equals(canonical(b));
    }
    private static String canonical(String json) {
        return new String(CanonicalJson.bytes(CanonicalJson.parse(json)), StandardCharsets.UTF_8);
    }
    private static boolean json(String json) {
        try { return json != null && CanonicalJson.parse(json) != null; }
        catch (RuntimeException invalid) { return false; }
    }
    private static boolean jsonObject(String json) {
        try { return json != null && CanonicalJson.parse(json).isObject(); }
        catch (RuntimeException invalid) { return false; }
    }
    private static boolean text(String value, int maxBytes) {
        return value != null && !value.isBlank() && value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
    private static boolean id(String value) { return value != null && value.matches("[A-Za-z0-9._:-]{1,64}"); }
    private static Integer number(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static Long longNumber(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static byte[] sha(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
