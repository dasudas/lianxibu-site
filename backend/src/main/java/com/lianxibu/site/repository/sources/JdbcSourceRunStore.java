package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.protocol.WireValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Calendar;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Persisted logical windows and current fenced run lease. */
public final class JdbcSourceRunStore {
    public record Row(String id, String sourceId, String runKind, LocalDate businessDate,
                      long generation, Instant windowStart, Instant windowEnd,
                      long configVersion, String configSnapshotJson, String activationStage,
                      long revision, String deliveryState, String claimNodeId,
                      byte[] claimTokenHash, byte[] claimTokenCiphertext,
                      String claimKeyId, Instant leaseUntil) {
        @Override public String toString() {
            return "SourceRunRow[id=" + id + ",state=" + deliveryState + "]";
        }
    }
    public record NewRun(String id, String sourceId, String runKind, LocalDate businessDate,
                         long generation, Instant windowStart, Instant windowEnd,
                         String baseRunId, long configVersion, String configSnapshotJson,
                         String activationStage, String fetchMode, String reason, Instant now) { }
    private static final String COLUMNS = "r.id,r.source_id,r.run_kind,r.business_date,r.generation,"
            + "r.window_start,r.window_end,r.config_version,r.config_snapshot_json,"
            + "r.activation_stage,r.revision,r.delivery_state,r.claim_node_id,r.claim_token_hash,"
            + "r.claim_token_ciphertext,r.claim_key_id,r.lease_until";
    private static final RowMapper<Row> MAPPER = (rs, ignored) -> new Row(
            rs.getString("id"), rs.getString("source_id"), rs.getString("run_kind"),
            LocalDate.parse(rs.getString("business_date")), rs.getLong("generation"),
            instant(rs, "window_start"), instant(rs, "window_end"),
            rs.getLong("config_version"), rs.getString("config_snapshot_json"),
            rs.getString("activation_stage"), rs.getLong("revision"),
            rs.getString("delivery_state"), rs.getString("claim_node_id"),
            rs.getBytes("claim_token_hash"), rs.getBytes("claim_token_ciphertext"),
            rs.getString("claim_key_id"), instant(rs, "lease_until"));
    private final JdbcTemplate jdbc;

    public JdbcSourceRunStore(DataSource source) {
        if (source == null) throw new IllegalArgumentException("source run data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Row locked(String runId) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM source_runs r WHERE r.id=? FOR UPDATE",
                MAPPER, runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Row byId(String runId) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM source_runs r WHERE r.id=?",
                MAPPER, runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Row natural(String sourceId, String kind, LocalDate day, long generation) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM source_runs r"
                + " WHERE r.source_id=? AND r.run_kind=? AND r.business_date=? AND r.generation=?",
                MAPPER, sourceId, kind, day.toString(), generation);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public long nextGeneration(String sourceId, String kind, LocalDate day) {
        Long current = jdbc.queryForObject("SELECT MAX(generation) FROM source_runs"
                + " WHERE source_id=? AND run_kind=? AND business_date=?", Long.class,
                sourceId, kind, day.toString());
        return current == null ? 1 : current + 1;
    }

    public void insert(NewRun value) {
        mutate("INSERT INTO source_runs(id,created_at,revision,source_id,run_kind,"
                        + "business_date,generation,window_start,window_end,base_run_id,"
                        + "config_version,config_snapshot_json,activation_stage,fetch_mode,"
                        + "collection_reason,delivery_state,fetch_state,analysis_state,"
                        + "accepted_count,excluded_count,pending_count,coverage_json,job_refs_json)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), timestamp(value.now()), 0, value.sourceId(), value.runKind(),
                value.businessDate().toString(), value.generation(),
                timestamp(value.windowStart()), timestamp(value.windowEnd()), value.baseRunId(),
                value.configVersion(), value.configSnapshotJson(), value.activationStage(),
                value.fetchMode(), value.reason(), "pending", "pending", "waiting_input",
                0, 0, 0, "{}", "[]");
    }

    public Row claimable(List<String> sourceIds, String kind, Instant now) {
        if (sourceIds == null || sourceIds.isEmpty()) return null;
        String placeholders = String.join(",", java.util.Collections.nCopies(sourceIds.size(), "?"));
        String sql = "SELECT " + COLUMNS + " FROM source_runs r JOIN sources s ON s.id=r.source_id"
                + " WHERE r.source_id IN (" + placeholders + ") AND r.run_kind "
                + ("probe".equals(kind) ? "= 'probe'" : "<> 'probe'")
                + " AND r.delivery_state IN ('pending','claimed','receiving')"
                + " AND (r.lease_until IS NULL OR r.lease_until<=?)"
                + " AND s.config_revision=r.config_version"
                + " AND s.activation_stage=r.activation_stage"
                + " AND (r.run_kind='probe' OR s.config_valid_until>?)"
                + " ORDER BY r.business_date,r.id LIMIT 1 FOR UPDATE";
        List<Object> args = new ArrayList<>(sourceIds);
        args.add(timestamp(now));
        args.add(timestamp(now));
        List<Row> rows = rows(sql, args.toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public static final class CapacityGuarded extends RuntimeException {
        public CapacityGuarded(){super("capacity_guard");}
    }

    /** Bounded non-locking day discovery. New rows after this read wait for the next claim. */
    public Set<LocalDate> dailyClaimDays(List<String> sourceIds,Instant now){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("discover source claim days outside transaction");
        bounded(sourceIds);java.util.Objects.requireNonNull(now);if(sourceIds.isEmpty())return Set.of();
        String placeholders=String.join(",",java.util.Collections.nCopies(sourceIds.size(),"?"));var args=new ArrayList<Object>(sourceIds);args.add(timestamp(now));args.add(timestamp(now));
        var days=jdbc.query("SELECT r.business_date FROM source_runs r JOIN sources s ON s.id=r.source_id WHERE r.source_id IN ("+placeholders+") AND r.run_kind='daily' AND r.activation_stage='production' AND s.activation_stage='production' AND r.delivery_state IN ('pending','claimed','receiving') AND (r.lease_until IS NULL OR r.lease_until<=?) AND s.config_revision=r.config_version AND s.config_valid_until>? GROUP BY r.business_date ORDER BY r.business_date LIMIT 33",ps->bind(ps,args.toArray()),(rs,index)->LocalDate.parse(rs.getString(1)));
        if(days.size()>32)throw new CapacityGuarded();return Set.copyOf(days);
    }

    /** The caller must also verify the selected actual run's original permanent receipt. */
    public Row claimable(List<String> sourceIds,String kind,Instant now,List<String> allowedDailyIds){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("approved source run selection requires actual writer");
        bounded(sourceIds);bounded(allowedDailyIds);java.util.Objects.requireNonNull(now);if(sourceIds.isEmpty())return null;
        String placeholders=String.join(",",java.util.Collections.nCopies(sourceIds.size(),"?"));var args=new ArrayList<Object>(sourceIds);args.add(timestamp(now));args.add(timestamp(now));
        String daily="";if(!"probe".equals(kind)){
            daily=allowedDailyIds.isEmpty()?" AND r.run_kind<>'daily'":" AND (r.run_kind<>'daily' OR (r.activation_stage='production' AND r.id IN ("+String.join(",",java.util.Collections.nCopies(allowedDailyIds.size(),"?"))+")))";args.addAll(allowedDailyIds);
        }
        String columns=COLUMNS.replace("r.config_snapshot_json,","CASE WHEN OCTET_LENGTH(r.config_snapshot_json)<=65536 THEN r.config_snapshot_json ELSE NULL END AS config_snapshot_json,");
        var rows=rows("SELECT "+columns+" FROM source_runs r JOIN sources s ON s.id=r.source_id WHERE r.source_id IN ("+placeholders+") AND r.run_kind "+("probe".equals(kind)?"='probe'":"<>'probe'")+" AND r.delivery_state IN ('pending','claimed','receiving') AND (r.lease_until IS NULL OR r.lease_until<=?) AND s.config_revision=r.config_version AND s.activation_stage=r.activation_stage AND (r.run_kind='probe' OR s.config_valid_until>?)"+daily+" ORDER BY r.business_date,r.id LIMIT 1 FOR UPDATE",args.toArray());
        return rows.isEmpty()?null:rows.getFirst();
    }

    private static void bounded(List<String> values){
        java.util.Objects.requireNonNull(values);if(values.size()>1000)throw new CapacityGuarded();if(Set.copyOf(values).size()!=values.size())throw new IllegalArgumentException("unique source run selector identities required");
        for(var value:values)WireValues.id(value,true);
    }

    public boolean claim(Row row, String nodeId, byte[] tokenHash, byte[] ciphertext,
                         String keyId, Instant leaseUntil, Instant now) {
        return mutate("UPDATE source_runs SET claim_node_id=?,claim_token_hash=?,"
                        + "claim_token_ciphertext=?,claim_key_id=?,lease_until=?,"
                        + "delivery_state='claimed',revision=revision+1 WHERE id=? AND revision=?"
                        + " AND delivery_state IN ('pending','claimed','receiving')"
                        + " AND (lease_until IS NULL OR lease_until<=?)",
                nodeId, tokenHash, ciphertext, keyId, timestamp(leaseUntil),
                row.id(), row.revision(), timestamp(now)) == 1;
    }

    public boolean acknowledge(Row row, String nodeId, byte[] tokenHash, Instant now) {
        return mutate("UPDATE source_runs SET delivery_state='receiving',"
                        + "fetch_state='running',node_id=?,"
                        + "started_at=COALESCE(started_at,?),revision=revision+1"
                        + " WHERE id=? AND revision=? AND claim_node_id=? AND claim_token_hash=?"
                        + " AND lease_until>? AND delivery_state='claimed'",
                nodeId, timestamp(now), row.id(), row.revision(), nodeId, tokenHash,
                timestamp(now)) == 1;
    }

    public boolean renew(Row row, String nodeId, byte[] tokenHash, Instant leaseUntil, Instant now) {
        return mutate("UPDATE source_runs SET lease_until=?,revision=revision+1"
                        + " WHERE id=? AND revision=? AND claim_node_id=? AND claim_token_hash=?"
                        + " AND lease_until>? AND delivery_state IN ('claimed','receiving')",
                timestamp(leaseUntil), row.id(), row.revision(), nodeId, tokenHash,
                timestamp(now)) == 1;
    }

    public boolean release(Row row, String nodeId, byte[] tokenHash, Instant now) {
        return mutate("UPDATE source_runs SET claim_node_id=NULL,claim_token_hash=NULL,"
                        + "claim_token_ciphertext=NULL,claim_key_id=NULL,lease_until=NULL,"
                        + "delivery_state='pending',revision=revision+1 WHERE id=? AND revision=?"
                        + " AND claim_node_id=? AND claim_token_hash=? AND lease_until>?"
                        + " AND delivery_state IN ('claimed','receiving')",
                row.id(), row.revision(), nodeId, tokenHash, timestamp(now)) == 1;
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name, utc());
        return value == null ? null : value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static void bind(java.sql.PreparedStatement statement,Object[] args)throws SQLException{
        for(int i=0;i<args.length;i++)if(args[i] instanceof Timestamp value)statement.setTimestamp(i+1,value,utc());else statement.setObject(i+1,args[i]);
    }
    private int mutate(String statement,Object...args){return jdbc.update(statement,ps->bind(ps,args));}
    private List<Row> rows(String statement,Object...args){return jdbc.query(statement,ps->bind(ps,args),MAPPER);}
}
