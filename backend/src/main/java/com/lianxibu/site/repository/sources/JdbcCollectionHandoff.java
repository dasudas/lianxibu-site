package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.LocalDate;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ArrayNode;

/** Reads only original run facts and appends a durable jobs reference without replacing prior references. */
public final class JdbcCollectionHandoff {
    public record Row(String runId, String sourceId, String runKind, LocalDate businessDate,
                      long generation, String category, String activationStage, long configVersion,
                      String configHash, String manifestHash, String deliveryState, String fetchState,
                      long expected, long accepted, long excluded, long pending, long revision,
                      String jobRefs,String sourceIdentity) { }
    private final JdbcTemplate jdbc;
    public JdbcCollectionHandoff(DataSource source) { jdbc = new JdbcTemplate(source); }
    public Row locked(String id) {
        var rows = jdbc.query("SELECT r.*,s.category AS source_category,s.id AS source_identity FROM source_runs r"
                + " JOIN sources s ON s.id=r.source_id WHERE r.id=? FOR UPDATE", (rs, n) -> {
            if ("probe".equals(rs.getString("run_kind")))
                return new Row(rs.getString("id"),rs.getString("source_id"),"probe",null,0,null,null,0,
                        null,null,null,null,0,0,0,0,0,null,rs.getString("source_identity"));
            var manifest = rs.getBytes("manifest_hash");
            var config = CanonicalJson.parse(rs.getString("config_snapshot_json"));
            if (!config.isObject() || rs.getObject("expected_count") == null)
                throw new IllegalStateException("collection facts unavailable");
            return new Row(rs.getString("id"),rs.getString("source_id"),rs.getString("run_kind"),
                    LocalDate.parse(rs.getString("business_date")),rs.getLong("generation"),rs.getString("source_category"),
                    rs.getString("activation_stage"),rs.getLong("config_version"),
                    CanonicalJson.hash(CanonicalJson.Domain.ACTION,config),
                    manifest == null ? null : HexFormat.of().formatHex(manifest),rs.getString("delivery_state"),
                    rs.getString("fetch_state"),rs.getLong("expected_count"),rs.getLong("accepted_count"),
                    rs.getLong("excluded_count"),rs.getLong("pending_count"),rs.getLong("revision"),
                    rs.getString("job_refs_json"),rs.getString("source_identity"));
        }, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void link(Row row, String intentId) {
        references(row.jobRefs());
        var node = CanonicalJson.parse(row.jobRefs());
        if (!node.isArray()) throw new IllegalStateException("collection references unavailable");
        var seen = new java.util.HashSet<String>();
        for (var ref : node) {
            if (!ref.isTextual() || !ref.asText().matches("[A-Za-z0-9._:-]{1,64}") || !seen.add(ref.asText()))
                throw new IllegalStateException("collection references invalid");
        }
        if (seen.contains(intentId)) return;
        ((ArrayNode)node).add(intentId);
        String json = new String(CanonicalJson.bytes(node), java.nio.charset.StandardCharsets.UTF_8);
        if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new IllegalStateException("collection references exceed limit");
        if (jdbc.update("UPDATE source_runs SET job_refs_json=?,revision=revision+1 WHERE id=? AND revision=?",
                json,row.runId(),row.revision()) != 1) throw new IllegalStateException("collection handoff changed");
    }
    public static java.util.List<String> references(String raw){
        if(raw==null||raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new IllegalStateException("collection references unavailable");
        var node=CanonicalJson.parse(raw);if(!node.isArray()||CanonicalJson.bytes(node).length>65536)throw new IllegalStateException("collection references unavailable");
        var seen=new java.util.LinkedHashSet<String>();
        for(var ref:node)if(!ref.isTextual()||!ref.asText().matches("[A-Za-z0-9._:-]{1,64}")||!seen.add(ref.asText()))throw new IllegalStateException("collection references invalid");
        return java.util.List.copyOf(seen);
    }
}
