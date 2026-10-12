package com.lianxibu.site.repository.jobs;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact root facts for a dispatch check in the caller's shared writer transaction. */
public final class JdbcDispatchInputs {
    public record Root(String id,String logicalKey,byte[] keyHash,long generation,Long latest,String type,String state,String inputState,byte[] inputHash,Instant cutoff,LocalDate businessDate,long revision,long stagePart,Integer calls,Integer maximum) { }
    public record Request(String id,String root,Integer number,byte[] inputHash,String snapshotOwner,String request,String state) {
        public Request {inputHash=inputHash==null?null:inputHash.clone();}
        @Override public byte[] inputHash(){return inputHash==null?null:inputHash.clone();}
        @Override public String toString(){return "SourceAttemptRequest[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcDispatchInputs(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Root read(String id){
        var rows=jdbc.query("SELECT r.id,r.logical_key,r.logical_key_hash,r.generation,(SELECT MAX(g.generation) FROM jobs g WHERE g.record_kind='generation_intent' AND g.logical_key_hash=r.logical_key_hash),r.task_type,r.state,r.input_state,r.input_hash,r.input_cutoff_at,r.business_date,r.revision,r.stage_part,r.call_count,r.max_attempts FROM jobs r WHERE r.id=? AND r.record_kind='logical_task'",(rs,n)->new Root(rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getLong(4),rs.getObject(5)==null?null:rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getBytes(9),rs.getTimestamp(10)==null?null:rs.getTimestamp(10).toInstant(),rs.getString(11)==null?null:LocalDate.parse(rs.getString(11)),rs.getLong(12),rs.getLong(13),rs.getObject(14)==null?null:rs.getInt(14),rs.getObject(15)==null?null:rs.getInt(15)),id);
        return rows.size()==1?rows.getFirst():null;
    }
    public List<Request> requests(String root) {
        JdbcFrozenInputs.requireTx();
        return jdbc.query("SELECT id,root_job_id,attempt_no,input_hash,snapshot_owner_id,CASE WHEN OCTET_LENGTH(request_json) BETWEEN 1 AND 2097152 THEN request_json ELSE NULL END,state,OCTET_LENGTH(request_json) FROM jobs WHERE record_kind='ai_attempt' AND root_job_id=? ORDER BY attempt_no,id LIMIT 4",(rs,n)->{
            long bytes=rs.getLong(8);if(rs.wasNull()||bytes<1||bytes>2097152)throw new IllegalStateException("source attempt request storage unavailable");
            return new Request(rs.getString(1),rs.getString(2),rs.getObject(3)==null?null:rs.getInt(3),rs.getBytes(4),rs.getString(5),rs.getString(6),rs.getString(7));
        },root);
    }
}
