package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Permanent owner command and original Run response; never stores a lease token. */
public final class JdbcOwnerRunReceipts {
    public record Receipt(String commandHash,String response) { }
    private static final String SCOPE="owner-run-create-v1";
    private final JdbcTemplate jdbc;
    public JdbcOwnerRunReceipts(DataSource source){jdbc=new JdbcTemplate(source);}
    public Receipt read(String actor,String key){
        var rows=jdbc.query("SELECT request_hash,response_json,business_hash,ack_json FROM request_receipts WHERE actor_id=? AND scope=? AND key_hash=? AND receipt_kind='admin_action'",
            (rs,n)->{String json=rs.getString(2),hash=HexFormat.of().formatHex(rs.getBytes(1));if(json==null||!MessageDigest.isEqual(rs.getBytes(3),sha(canonical(json)))
                ||!hash.equals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(rs.getString(4)))))throw new IllegalStateException("run receipt unavailable");return new Receipt(hash,json);},actor,SCOPE,sha(key));
        if(rows.size()>1)throw new IllegalStateException("duplicate run receipt");return rows.isEmpty()?null:rows.getFirst();
    }
    public void save(String actor,String key,String hash,String command,String run,String response,Instant now){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("run receipt requires writer transaction");
        String json=canonical(response);
        jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,run_id,original_status,current_status,created_at,revision,request_key_raw,business_hash,ack_json) VALUES(?,?,?,?,200,?,NULL,?,'admin_action',?,?,?,?,'applied','applied',?,0,?,?,?)",
            actor,SCOPE,key,HexFormat.of().parseHex(hash),json,HexFormat.of().formatHex(sha(SCOPE+"\n"+actor+"\n"+key)),actor,SCOPE,sha(key),run,Timestamp.from(now),key,sha(json),canonical(command));
    }
    private static String canonical(String json){return new String(CanonicalJson.bytes(CanonicalJson.parse(json)),StandardCharsets.UTF_8);}
    private static byte[] sha(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
