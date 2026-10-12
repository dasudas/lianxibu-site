package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Permanent manual decisions in the existing receipt table; no schema initialization. */
public final class JdbcManualSourceAdmissions {
    public record Receipt(String sourceId,long revision,String activationStage) { }
    private static final String SCOPE="manual-source-admission-v1";
    private final JdbcTemplate jdbc;
    public JdbcManualSourceAdmissions(DataSource source){jdbc=new JdbcTemplate(source);}
    public boolean actorExists(String actor){return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?",Integer.class,actor)==1;}
    public Receipt find(String actor,byte[] key,byte[] hash) {
        var rows=jdbc.query("SELECT request_hash,result_ref_json FROM request_receipts WHERE actor_id=? AND scope=? AND key_hash=?",
                (rs,n)->{
                    if(!Arrays.equals(hash,rs.getBytes(1)))throw new IllegalArgumentException("manual action key conflict");
                    var value=CanonicalJson.parse(rs.getString(2));
                    return new Receipt(value.get("source_id").asText(),value.get("revision").longValue(),value.get("activation_stage").asText());
                },actor,SCOPE,key);
        if(rows.size()>1)throw new IllegalStateException("duplicate manual receipt");
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void save(String actor,String actionKey,byte[] key,byte[] hash,String operation,String manifest,Receipt result,Instant now) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("manual receipt transaction required");
        var json=JsonMapper.shared().createObjectNode();json.put("source_id",result.sourceId());json.put("revision",result.revision());json.put("activation_stage",result.activationStage());
        jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,status_code,response_json,expires_at,"
                +"id,receipt_kind,actor_id,source_id,scope,key_hash,original_status,current_status,ack_json,result_ref_json,created_at,revision,request_key_raw,business_hash)"
                +" VALUES(?,?,?,?,200,NULL,NULL,?,?,?,?,?,?, 'applied','applied',?,?,?,?,?,?)",
                actor,"manual_"+operation,actionKey,hash,UUID.randomUUID().toString(),"admin_action",actor,result.sourceId(),SCOPE,key,
                manifest,json.toString(),Timestamp.from(now),result.revision(),actionKey,hash);
    }
}
