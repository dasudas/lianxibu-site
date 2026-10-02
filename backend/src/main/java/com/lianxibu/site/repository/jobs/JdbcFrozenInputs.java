package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Immutable bounded input chunks. Hashes always use application canonical bytes. */
public final class JdbcFrozenInputs {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final int CHUNK_BYTES=96*1024, SUMMARY_BYTES=64*1024;
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    public JdbcFrozenInputs(DataSource source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source)); records=new JdbcJobRecords(source);
    }
    public void save(String owner,String snapshot,String inputHash,Instant now) {
        requireTx();
        var body=CanonicalJson.parse(snapshot);
        if(!body.isObject()||!CanonicalJson.hash(CanonicalJson.Domain.INPUT,body).equals(inputHash))throw invalid();
        byte[] bytes=CanonicalJson.bytes(body);
        var manifest=JSON.createObjectNode();manifest.put("schema_version","frozen-input-manifest-v1");
        manifest.put("owner_job_id",owner);manifest.put("input_hash",inputHash);
        var parts=manifest.putArray("parts");var payloads=new ArrayList<String>();
        for(int offset=0;offset<bytes.length;offset+=CHUNK_BYTES) {
            var chunk=JSON.createObjectNode();chunk.put("schema_version","frozen-input-chunk-v1");
            chunk.put("bytes_base64",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+CHUNK_BYTES))));
            String payload=canonical(chunk),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk);
            var part=parts.addObject();part.put("part_no",payloads.size());part.put("payload_hash",hash);payloads.add(payload);
        }
        String summary=canonical(manifest);
        if(summary.getBytes(StandardCharsets.UTF_8).length>SUMMARY_BYTES)throw invalid();
        var existing=jdbc.query("SELECT id,input_snapshot_json FROM jobs WHERE id=? AND record_kind='logical_task'",(rs,i)->new String[]{rs.getString(1),rs.getString(2)},owner);
        if(existing.size()!=1||!owner.equals(existing.getFirst()[0]))throw invalid();
        if(existing.getFirst()[1]!=null) {
            if(!CanonicalJson.parse(existing.getFirst()[1]).equals(manifest)||!read(owner,inputHash).equals(canonical(body)))throw invalid();
            return;
        }
        for(int n=0;n<payloads.size();n++)records.putPart(new JdbcJobRecords.Part(
            stableId("input",owner+":"+n),owner,"input","input",n,payloads.get(n),parts.get(n).path("payload_hash").asText()),now);
        if(jdbc.update("UPDATE jobs SET input_snapshot_json=?,input_hash=? WHERE id=? AND record_kind='logical_task' AND input_snapshot_json IS NULL",
            summary,HexFormat.of().parseHex(inputHash),owner)!=1)throw invalid();
    }
    public String read(String owner,String inputHash) {
        var summaries=jdbc.query("SELECT id,input_snapshot_json,input_hash FROM jobs WHERE id=? AND record_kind='logical_task'",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBytes(3)},owner);
        if(summaries.size()!=1||!owner.equals(summaries.getFirst()[0])||summaries.getFirst()[1]==null||!Arrays.equals((byte[])summaries.getFirst()[2],HexFormat.of().parseHex(inputHash)))throw invalid();
        String summary=(String)summaries.getFirst()[1];if(summary.getBytes(StandardCharsets.UTF_8).length>SUMMARY_BYTES)throw invalid();
        JsonNode manifest=CanonicalJson.parse(summary);
        if(!manifest.isObject()||manifest.size()!=4||!"frozen-input-manifest-v1".equals(manifest.path("schema_version").asText())||!owner.equals(manifest.path("owner_job_id").asText())||!inputHash.equals(manifest.path("input_hash").asText())||!manifest.path("parts").isArray())throw invalid();
        var rows=jdbc.query("SELECT part_no,payload_json,payload_hash FROM jobs WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='input' AND stamp_id='input' ORDER BY part_no",(rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getBytes(3)},owner);
        var parts=manifest.get("parts");if(rows.isEmpty()||parts.size()!=rows.size())throw invalid();
        var bytes=new ByteArrayOutputStream();
        for(int n=0;n<rows.size();n++) {
            var row=rows.get(n);var part=parts.get(n);var payload=CanonicalJson.parse((String)row[1]);
            if((long)row[0]!=n||!part.isObject()||part.size()!=2||!part.path("part_no").isIntegralNumber()||part.path("part_no").asLong()!=n||!payload.isObject()||payload.size()!=2||!"frozen-input-chunk-v1".equals(payload.path("schema_version").asText())||!payload.path("bytes_base64").isTextual())throw invalid();
            String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,payload);
            if(!hash.equals(part.path("payload_hash").asText())||!Arrays.equals((byte[])row[2],HexFormat.of().parseHex(hash)))throw invalid();
            byte[] chunk;try{chunk=Base64.getDecoder().decode(payload.get("bytes_base64").asText());}catch(IllegalArgumentException bad){throw invalid();}
            if(chunk.length<1||chunk.length>CHUNK_BYTES||n<rows.size()-1&&chunk.length!=CHUNK_BYTES||!Base64.getEncoder().encodeToString(chunk).equals(payload.get("bytes_base64").asText()))throw invalid();
            bytes.writeBytes(chunk);
        }
        // Canonical parser checks UTF-8, including a multi-byte scalar spanning chunks.
        var input=CanonicalJson.parse(bytes.toByteArray());
        if(!CanonicalJson.hash(CanonicalJson.Domain.INPUT,input).equals(inputHash)||!Arrays.equals(CanonicalJson.bytes(input),bytes.toByteArray()))throw invalid();
        return canonical(input);
    }
    public static String stableId(String kind,String key){var node=JSON.createObjectNode();node.put("kind",kind);node.put("key",key);return CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,node);}
    public static String canonical(JsonNode node){return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);}
    public static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("frozen input requires shared writer transaction");}
    private static IllegalStateException invalid(){return new IllegalStateException("immutable frozen input proof unavailable");}
}
