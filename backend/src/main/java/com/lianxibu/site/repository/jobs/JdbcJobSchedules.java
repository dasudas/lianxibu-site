package com.lianxibu.site.repository.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Jobs-only permanent scheduling proof inputs, never an outward wire projection. */
public final class JdbcJobSchedules {
    public record Job(String id,String kind,long revision,String taskType,LocalDate businessDate,
                      Long generation,String logicalKey,byte[] inputHash,String manifest) {
        public Job {inputHash=inputHash==null?null:inputHash.clone();}
        @Override public byte[] inputHash(){return inputHash==null?null:inputHash.clone();}
        @Override public String toString(){return "JobScheduleProof[id="+id+"]";}
    }
    public record Admission(String target,byte[] targetHash,String payload,byte[] payloadHash,Instant actedAt) {
        public Admission{targetHash=targetHash==null?null:targetHash.clone();payloadHash=payloadHash==null?null:payloadHash.clone();}
        @Override public byte[] targetHash(){return targetHash==null?null:targetHash.clone();}
        @Override public byte[] payloadHash(){return payloadHash==null?null:payloadHash.clone();}
        @Override public String toString(){return "QueueAdmissionProof";}
    }
    public record Attempt(String id,String root,String owner,byte[] inputHash,String request) {
        public Attempt{inputHash=inputHash==null?null:inputHash.clone();}
        @Override public byte[] inputHash(){return inputHash==null?null:inputHash.clone();}
        @Override public String toString(){return "PreparedAttemptProof[id="+id+"]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcJobSchedules(DataSource source){jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(source));}
    public Job job(String id){var rows=jdbc.query("SELECT id,record_kind,revision,task_type,business_date,generation,logical_key,input_hash,input_snapshot_json FROM jobs WHERE id=?",(rs,n)->new Job(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getObject(5,LocalDate.class),nullableLong(rs,6),rs.getString(7),rs.getBytes(8),rs.getString(9)),id);return rows.isEmpty()?null:rows.getFirst();}
    public List<Admission> admissions(String canonicalTarget,byte[] targetHash){return jdbc.query("SELECT target_key_json,target_key_hash,action_payload,action_payload_hash,acted_at FROM jobs WHERE record_kind='disposition' AND target_type='generation_prepare' AND (target_key_hash=? OR target_key_json=?) ORDER BY acted_at,id LIMIT 101",(rs,n)->new Admission(rs.getString(1),rs.getBytes(2),rs.getString(3),rs.getBytes(4),rs.getTimestamp(5).toInstant()),targetHash,canonicalTarget);}
    public Attempt attempt(String id){var rows=jdbc.query("SELECT id,root_job_id,snapshot_owner_id,input_hash,request_json FROM jobs WHERE id=? AND record_kind='ai_attempt' AND attempt_no=1",(rs,n)->new Attempt(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBytes(4),rs.getString(5)),id);return rows.isEmpty()?null:rows.getFirst();}
    private static Long nullableLong(ResultSet rs,int i)throws SQLException{long n=rs.getLong(i);return rs.wasNull()?null:n;}
}
