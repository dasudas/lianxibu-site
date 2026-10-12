package com.lianxibu.site.repository.records;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** User-scoped current records and permanent receipts in one caller-owned transaction. */
public final class JdbcRecordWrites {
    public record Row(String userId,String packageId,String objectKind,String objectId,long revision,String status,
                      String selfAssessment,String note,String artifactUrl,String responseText,Long confirmedBodyRevision,
                      Instant updatedAt,boolean isRead,boolean isFavorite,boolean isIgnored) { }
    private static final String SCOPE="private-record-v1";
    private final JdbcTemplate jdbc;
    public JdbcRecordWrites(DataSource source){jdbc=new JdbcTemplate(source);}
    public String committed(String user,byte[] key,byte[] commandHash){
        var rows=jdbc.query("SELECT request_hash,response_json FROM request_receipts WHERE actor_id=? AND scope=? AND key_hash=?",
                (rs,i)->{if(!Arrays.equals(commandHash,rs.getBytes(1)))throw new IllegalArgumentException("record action conflict");return rs.getString(2);},user,SCOPE,key);
        if(rows.size()>1)throw new IllegalStateException("duplicate record receipt");return rows.isEmpty()?null:rows.getFirst();
    }
    public Row locked(String user,String pkg,String kind,String id){
        requireTx();var rows=jdbc.query("SELECT revision,status,self_assessment,note,artifact_url,response_text,confirmed_body_revision,updated_at,is_read,is_favorite,is_ignored FROM user_records WHERE user_id=? AND package_id=? AND object_kind=? AND object_id=? FOR UPDATE",
                (rs,i)->new Row(user,pkg,kind,id,rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,Long.class),rs.getTimestamp(8).toInstant(),rs.getBoolean(9),rs.getBoolean(10),rs.getBoolean(11)),user,pkg,kind,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void insert(Row r){
        requireTx();jdbc.update("INSERT INTO user_records(user_id,package_id,object_kind,object_id,revision,status,self_assessment,note,artifact_url,response_text,confirmed_body_revision,updated_at,is_read,is_favorite,is_ignored) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.userId(),r.packageId(),r.objectKind(),r.objectId(),r.revision(),r.status(),r.selfAssessment(),r.note(),r.artifactUrl(),r.responseText(),r.confirmedBodyRevision(),Timestamp.from(r.updatedAt()),r.isRead(),r.isFavorite(),r.isIgnored());
    }
    public void update(Row r,long expected){
        requireTx();if(jdbc.update("UPDATE user_records SET revision=?,status=?,self_assessment=?,note=?,artifact_url=?,response_text=?,confirmed_body_revision=?,updated_at=?,is_read=?,is_favorite=?,is_ignored=? WHERE user_id=? AND package_id=? AND object_kind=? AND object_id=? AND revision=?",
                r.revision(),r.status(),r.selfAssessment(),r.note(),r.artifactUrl(),r.responseText(),r.confirmedBodyRevision(),Timestamp.from(r.updatedAt()),r.isRead(),r.isFavorite(),r.isIgnored(),r.userId(),r.packageId(),r.objectKind(),r.objectId(),expected)!=1)throw new IllegalArgumentException("record revision conflict");
    }
    public void receipt(String user,String action,byte[] key,byte[] hash,String response,long revision,Instant now){
        requireTx();jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,original_status,current_status,created_at,revision,request_key_raw,business_hash) VALUES(?,?,?,?,200,?,NULL,?,?,?,?,?,'applied','applied',?,?,?,?)",
                user,"record_write",action,hash,response,UUID.randomUUID().toString(),"user_action",user,SCOPE,key,Timestamp.from(now),revision,action,hash);
    }
    public java.util.Set<String> completed(String user,String pkg,java.util.Set<String> ids){
        var found=new java.util.HashSet<String>();
        for(String id:ids){if(jdbc.queryForObject("SELECT COUNT(*) FROM user_records WHERE user_id=? AND package_id=? AND object_kind='node' AND object_id=? AND status='completed'",Integer.class,user,pkg,id)==1)found.add(id);}
        return java.util.Set.copyOf(found);
    }
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("record write transaction required");}
}
