package com.lianxibu.site.repository.learning;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcLearningPackages {
    public record Package(String id,String title,long revision,String state) { }
    public record Item(String kind,String id,String title,String body) { }
    public record Link(String fromKind,String fromId,String relation,String toKind,String toId) { }
    private final JdbcTemplate jdbc;
    public JdbcLearningPackages(DataSource source) { jdbc=new JdbcTemplate(source); }
    public Package read(String id,boolean lock) {
        if(lock)requireTx();
        var rows=jdbc.query("SELECT title,revision,state FROM learning_packages WHERE id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Package(id,rs.getString(1),rs.getLong(2),rs.getString(3)),id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<Item> items(String pkg) {
        requireTx();var rows=jdbc.query("SELECT kind,id,title,body_json FROM learning_items WHERE package_id=? ORDER BY kind,id LIMIT 10001",
                (rs,n)->new Item(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),pkg);
        if(rows.size()>10000)throw new IllegalStateException("capacity_guard");return rows;
    }
    public List<Link> links(String pkg) {
        requireTx();var rows=jdbc.query("SELECT from_kind,from_id,relation_kind,to_kind,to_id FROM learning_links WHERE package_id=? ORDER BY from_kind,from_id,relation_kind,to_kind,to_id LIMIT 50001",
                (rs,n)->new Link(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),pkg);
        if(rows.size()>50000)throw new IllegalStateException("capacity_guard");return rows;
    }
    public String receipt(String actor,byte[] key,byte[] hash) {
        var rows=jdbc.query("SELECT request_hash,response_json FROM request_receipts WHERE actor_id=? AND scope='learning-package-v1' AND key_hash=?",
                (rs,n)->{if(!Arrays.equals(hash,rs.getBytes(1)))throw new IllegalArgumentException("package command conflict");return rs.getString(2);},actor,key);
        if(rows.size()>1)throw new IllegalStateException("duplicate package receipt");return rows.isEmpty()?null:rows.getFirst();
    }
    public void transition(String id,long revision,String state,Instant now) {
        requireTx();if(jdbc.update("UPDATE learning_packages SET state=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",state,Timestamp.from(now),id,revision)!=1)throw new IllegalArgumentException("package revision conflict");
    }
    public void receipt(String actor,String key,byte[] keyHash,byte[] hash,String result,Instant now) {
        requireTx();jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,original_status,current_status,created_at,revision,request_key_raw,business_hash) VALUES(?,'learning-package-v1',?,?,200,?,NULL,?,'admin_action',?,'learning-package-v1',?,'applied','applied',?,1,?,?)",
                actor,key,hash,result,actionId(actor,key),actor,keyHash,Timestamp.from(now),key,hash);
    }
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("package writer transaction required");}
    public static String actionId(String actor,String key){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("learning-package-v1\n"+actor+"\n"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
