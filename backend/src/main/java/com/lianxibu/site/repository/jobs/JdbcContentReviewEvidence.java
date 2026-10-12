package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Immutable body evidence and permanent review action receipts, inside shared writes. */
public final class JdbcContentReviewEvidence {
    public static final class CommandMismatch extends RuntimeException { }
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    public JdbcContentReviewEvidence(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);}
    public void archive(ContentVersionKey target,String body,String inputHash,String actor,Instant now){
        JdbcFrozenInputs.requireTx();String version=JdbcFrozenContentVersions.version(target),key="version:"+JdbcResultState.sha(version),stamp=JdbcFrozenInputs.stableId("review-body",version),part=JdbcFrozenInputs.stableId("review-body-part",version);
        var json=CanonicalJson.parse(body);records.putPart(new JdbcJobRecords.Part(part,target.inputJobId(),"review",stamp,0,JdbcFrozenInputs.canonical(json),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,json)),now);
        var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version","content-version-evidence-v1");payload.put("owner_id",target.inputJobId());payload.put("input_hash",inputHash);payload.put("part_id",part);payload.put("stamp_id",stamp);
        records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("review-body-evidence",version),key,"content_review_version",version,actor,"immutable edited content version",JdbcFrozenInputs.canonical(payload)),now);
    }
    public JsonNode receipt(String key,String actor,String commandHash){
        var rows=jdbc.query("SELECT target_type,target_key_json,target_key_hash,operator_id,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getString(4),rs.getString(5),rs.getBytes(6)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();var row=rows.getFirst();var target=CanonicalJson.parse((String)row[1]);var payload=CanonicalJson.parse((String)row[4]);
        if(!"content_review_action".equals(row[0])||!Arrays.equals((byte[])row[2],HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(target))))||!Arrays.equals((byte[])row[5],HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(payload))))||!"content-review-receipt-v1".equals(payload.path("schema_version").asText())||!target.path("content_kind").asText().equals(payload.path("kind").asText())||!target.path("id").asText().equals(payload.path("id").asText()))throw invalid();
        if(!actor.equals(row[3])||!commandHash.equals(payload.path("command_hash").asText()))throw new CommandMismatch();return payload;
    }
    public void append(String key,String actor,String reason,String kind,String id,JsonNode payload,Instant now){
        JdbcFrozenInputs.requireTx();var target=JsonMapper.shared().createObjectNode();target.put("kind","content");target.put("content_kind",kind);target.put("id",id);
        records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("review-action",key),key,"content_review_action",JdbcFrozenInputs.canonical(target),actor,reason,JdbcFrozenInputs.canonical(payload)),now);
    }
    private static IllegalStateException invalid(){return new IllegalStateException("content review receipt unavailable");}
}
