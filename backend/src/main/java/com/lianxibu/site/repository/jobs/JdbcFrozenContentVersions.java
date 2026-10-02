package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Immutable version evidence only; current contents rows cannot prove an old body. */
public final class JdbcFrozenContentVersions {
    public record Proof(String versionJson,String inputHash,String snapshotJson,
                        String resultJson,String bodyJson,String attemptId) { }
    private final JdbcTemplate jdbc;
    private final JdbcFrozenInputs inputs;
    private final JdbcResultState results;
    public JdbcFrozenContentVersions(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));inputs=new JdbcFrozenInputs(source);results=new JdbcResultState(source);}
    public Proof read(ContentVersionKey target){
        String version=version(target);if(target.inputJobId()==null)return null;
        var archives=jdbc.query("SELECT target_key_json,target_key_hash,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND target_type='content_review_version' AND target_key_hash=UNHEX(?)",(rs,n)->new Object[]{rs.getString(1),rs.getBytes(2),rs.getString(3),rs.getBytes(4)},JdbcResultState.sha(version));
        for(var row:archives){var node=CanonicalJson.parse((String)row[0]);if(!version.equals(JdbcFrozenInputs.canonical(node))||!Arrays.equals((byte[])row[1],HexFormat.of().parseHex(JdbcResultState.sha(version))))throw unavailable();var payload=payload(row,2,3);if(!"content-version-evidence-v1".equals(payload.path("schema_version").asText())||!target.inputJobId().equals(payload.path("owner_id").asText()))throw unavailable();
            String hash=payload.path("input_hash").asText(),part=payload.path("part_id").asText();var parts=jdbc.query("SELECT id,owner_job_id,purpose,stamp_id,part_no,payload_json,payload_hash FROM jobs WHERE id=? AND record_kind='snapshot_part'",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getBytes(7)},part);
            if(parts.size()!=1)throw unavailable();var stored=parts.getFirst();var body=CanonicalJson.parse((String)stored[5]);if(!part.equals(stored[0])||!target.inputJobId().equals(stored[1])||!"review".equals(stored[2])||!payload.path("stamp_id").asText().equals(stored[3])||(long)stored[4]!=0||!Arrays.equals((byte[])stored[6],HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body))))throw unavailable();
            return new Proof(version,hash,inputs.read(target.inputJobId(),hash),null,(String)stored[5],null);
        }
        var attempts=jdbc.query("SELECT id FROM jobs WHERE record_kind='ai_attempt' AND root_job_id=? ORDER BY attempt_no",(rs,n)->rs.getString(1),target.inputJobId());
        for(String attempt:attempts){var node=JsonMapper.shared().createObjectNode();node.put("root_id",target.inputJobId());node.put("attempt_id",attempt);String targetHash=JdbcResultState.sha(JdbcFrozenInputs.canonical(node));
            var receipts=jdbc.query("SELECT target_key_json,target_key_hash,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND target_type='result_application' AND target_key_hash=UNHEX(?)",(rs,n)->new Object[]{rs.getString(1),rs.getBytes(2),rs.getString(3),rs.getBytes(4)},targetHash);
            for(var row:receipts){if(!JdbcFrozenInputs.canonical(node).equals(JdbcFrozenInputs.canonical(CanonicalJson.parse((String)row[0])))||!Arrays.equals((byte[])row[1],HexFormat.of().parseHex(targetHash)))throw unavailable();var receipt=payload(row,2,3);if(!"result-application-receipt-v1".equals(receipt.path("schema_version").asText())||!"APPLIED".equals(receipt.path("status").asText())||!receipt.has("content_version"))continue;
                var proven=ContentVersionKey.fromJson(receipt.get("content_version"));if(!target.equals(proven))continue;var root=results.root(target.inputJobId());String raw=results.retained(root);if(raw==null||root.appliedAt()==null||!root.resultHash().equals(receipt.path("result_hash").asText())||!root.hash().equals(receipt.path("input_hash").asText()))throw unavailable();
                return new Proof(version,root.hash(),inputs.read(target.inputJobId(),root.hash()),raw,null,attempt);
            }
        }
        return null;
    }
    private static JsonNode payload(Object[] row,int text,int hash){var payload=CanonicalJson.parse((String)row[text]);if(!Arrays.equals((byte[])row[hash],HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(payload)))))throw unavailable();return payload;}
    public static String version(ContentVersionKey v){var node=JsonMapper.shared().createObjectNode();node.put("kind","content_version");node.put("content_kind",v.contentKind());node.put("content_id",v.contentId());node.put("revision",v.revision());node.put("body_hash",v.bodyHash());node.put("input_job_id",v.inputJobId());ContentVersionKey.fromJson(node);return JdbcFrozenInputs.canonical(node);}
    private static IllegalStateException unavailable(){return new IllegalStateException("immutable content version evidence unavailable");}
}
