package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Physical roots and permanent lineage receipts in the existing jobs table. */
public final class JdbcGenerationLineage {
    public record Root(String id,String logicalKey,byte[] keyHash,long generation,long part,String type,String previous,String state,int calls) { }
    private final JdbcTemplate jdbc;private final JdbcJobRecords records;
    public JdbcGenerationLineage(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);}
    private static final String COLUMNS="id,logical_key,logical_key_hash,generation,stage_part,task_type,supersedes_job_id,state,call_count";
    private Root row(java.sql.ResultSet rs)throws java.sql.SQLException{return new Root(rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(9));}
    public Root root(String id){var rows=jdbc.query("SELECT "+COLUMNS+" FROM jobs WHERE id=? AND record_kind='logical_task'",(rs,n)->row(rs),id);return rows.size()==1?rows.getFirst():null;}
    public List<Root> branch(byte[] hash,long part){return jdbc.query("SELECT "+COLUMNS+" FROM jobs WHERE record_kind='logical_task' AND logical_key_hash=? AND stage_part=? ORDER BY generation LIMIT 10001",(rs,n)->row(rs),hash,part);}
    public JdbcJobRecords.Disposition receipt(String id,String action){
        var rows=jdbc.query("SELECT id,target_type,target_key_json,target_key_hash,operator_id,reason,action_payload,action_payload_hash,action_key_hash FROM jobs WHERE id=? AND record_kind='disposition'",(rs,n)->{
            String target=rs.getString(3),payload=rs.getString(7);
            if(!Arrays.equals(rs.getBytes(4),hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(target))))||!Arrays.equals(rs.getBytes(8),hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(payload))))||!Arrays.equals(rs.getBytes(9),hash(action)))throw unavailable();
            return new JdbcJobRecords.Disposition(rs.getString(1),action,rs.getString(2),target,rs.getString(5),rs.getString(6),payload);
        },id);return rows.size()==1?rows.getFirst():null;
    }
    public void append(Root root,String previous,JdbcJobRecords.Disposition receipt,Instant now){
        JdbcFrozenInputs.requireTx();if(jdbc.update("UPDATE jobs SET supersedes_job_id=? WHERE id=? AND record_kind='logical_task' AND state='waiting_input' AND call_count=0 AND supersedes_job_id IS NULL",previous,root.id())!=1)throw unavailable();records.appendDisposition(receipt,now);
    }
    public static byte[] hash(String text){try{return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static IllegalStateException unavailable(){return new IllegalStateException("generation lineage proof unavailable");}
}
