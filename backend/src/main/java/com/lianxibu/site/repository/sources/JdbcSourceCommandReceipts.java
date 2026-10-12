package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Permanent actor-scoped source command result, including the original public source snapshot. */
public final class JdbcSourceCommandReceipts {
    public record Receipt(String hash,String json) { }
    private static final String SCOPE="source-command-v1";
    private final JdbcTemplate jdbc;
    public JdbcSourceCommandReceipts(DataSource source){jdbc=new JdbcTemplate(source);}
    public Receipt read(String actor,String key){
        var rows=jdbc.query("SELECT request_hash,response_json FROM request_receipts WHERE actor_id=? AND scope=? AND key_hash=? AND receipt_kind='admin_action'",(rs,n)->new Receipt(HexFormat.of().formatHex(rs.getBytes(1)),rs.getString(2)),actor,SCOPE,sha(key));
        if(rows.size()>1)throw new IllegalStateException("duplicate source receipt");return rows.isEmpty()?null:rows.getFirst();
    }
    public void save(String actor,String key,String hash,String json,long revision,Instant now){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("source receipt requires write transaction");
        CanonicalJson.parse(json);
        jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,original_status,current_status,created_at,revision,request_key_raw,business_hash) VALUES(?,?,?,?,200,?,NULL,?,'admin_action',?,?,?,'applied','applied',?,?,?,?)",
                actor,SCOPE,key,HexFormat.of().parseHex(hash),json,id(actor,key),actor,SCOPE,sha(key),Timestamp.from(now),revision,key,HexFormat.of().parseHex(hash));
    }
    public static String id(String actor,String key){return HexFormat.of().formatHex(sha(SCOPE+"\n"+actor+"\n"+key));}
    private static byte[] sha(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
