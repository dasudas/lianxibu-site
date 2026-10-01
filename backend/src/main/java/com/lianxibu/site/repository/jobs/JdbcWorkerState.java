package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Physical jobs/worker_lock operations; caller owns the shared writer transaction. */
public final class JdbcWorkerState {
    public record Slot(int id,String state,long revision,long fencing,String attempt,String holder,
                       Instant lease,Instant occupiedSince) { }
    public record Prepared(String root,String attempt,int priority,Instant eligible,String rootState,
                           String attemptState,int calls,int maximum,long revision,String request,
                           String inputHash,String dependency,String dependencyState) { }
    private final JdbcTemplate jdbc;
    public JdbcWorkerState(DataSource source) { jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(source)); }
    public Slot slot() {
        var rows=jdbc.query("SELECT id,slot_state,slot_revision,fencing_token,job_id,holder,lease_until,occupied_since FROM worker_lock",
                (rs,n)->new Slot(rs.getInt(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getString(5),rs.getString(6),instant(rs.getTimestamp(7)),instant(rs.getTimestamp(8))));
        if(rows.size()!=1)throw new IllegalStateException("worker row must be unique");
        return rows.getFirst();
    }
    public List<Prepared> candidates(Instant now) {
        return jdbc.query("SELECT r.id,a.id,r.priority_no,r.eligible_at,r.state,a.state,r.call_count,r.max_attempts,r.revision,a.request_json,LOWER(HEX(a.input_hash)),r.dependency_id,d.state FROM jobs r JOIN jobs a ON a.root_job_id=r.id AND a.record_kind='ai_attempt' LEFT JOIN jobs d ON d.id=r.dependency_id WHERE r.record_kind='logical_task' AND r.state='queued' AND a.state='prepared' AND a.attempt_no=r.call_count+1 AND r.eligible_at<=? ORDER BY r.priority_no,r.eligible_at,r.id,a.id",
                (rs,n)->new Prepared(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getTimestamp(4).toInstant(),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getInt(8),rs.getLong(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13)),Timestamp.from(now));
    }
    public Slot reserve(Prepared selected,String owner,Instant lease,Instant now) {
        requireTx();var slot=slot();
        if(!"free".equals(slot.state())||slot.attempt()!=null||slot.holder()!=null||slot.lease()!=null||slot.occupiedSince()!=null||slot.revision()>=CanonicalJson.MAX_SAFE_INTEGER||slot.fencing()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
        one(jdbc.update("UPDATE jobs SET state='running',call_count=call_count+1,revision=revision+1,updated_at=? WHERE id=? AND record_kind='logical_task' AND state='queued' AND revision=? AND call_count=? AND call_count<max_attempts AND max_attempts=3",Timestamp.from(now),selected.root(),selected.revision(),selected.calls()));
        one(jdbc.update("UPDATE jobs SET state='running',worker_instance_id=?,dispatch_token=?,request_deadline=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='ai_attempt' AND state='prepared' AND root_job_id=? AND attempt_no=? AND send_authorized_at IS NULL",owner,slot.fencing()+1,Timestamp.from(now.plusSeconds(90)),Timestamp.from(now),selected.attempt(),selected.root(),selected.calls()+1));
        one(jdbc.update("UPDATE worker_lock SET slot_state='occupied',slot_revision=slot_revision+1,fencing_token=fencing_token+1,job_id=?,holder=?,lease_until=?,request_deadline=?,occupied_since=? WHERE id=? AND slot_state='free' AND slot_revision=? AND fencing_token=?",selected.attempt(),owner,Timestamp.from(lease),Timestamp.from(now.plusSeconds(90)),Timestamp.from(now),slot.id(),slot.revision(),slot.fencing()));
        return slot();
    }
    public boolean owns(String root,String attempt,String owner,long token,long revision,long rootRevision,Instant now,
                        String request,String hash) {
        var slot=slot();if(!"occupied".equals(slot.state())||!attempt.equals(slot.attempt())||!owner.equals(slot.holder())||token!=slot.fencing()||revision!=slot.revision()||slot.lease()==null||!now.isBefore(slot.lease()))return false;
        var rows=jdbc.query("SELECT a.request_json,LOWER(HEX(a.input_hash)) FROM jobs a JOIN jobs r ON r.id=a.root_job_id WHERE a.id=? AND a.root_job_id=? AND a.record_kind='ai_attempt' AND r.record_kind='logical_task' AND a.state='running' AND r.state='running' AND r.revision=? AND a.worker_instance_id=? AND a.dispatch_token=? AND r.call_count=a.attempt_no AND r.call_count BETWEEN 1 AND 3",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},attempt,root,rootRevision,owner,token);
        return rows.size()==1&&hash.equals(rows.getFirst()[1])&&java.util.Arrays.equals(CanonicalJson.bytes(CanonicalJson.parse(request)),CanonicalJson.bytes(CanonicalJson.parse(rows.getFirst()[0])));
    }
    public boolean authorized(String attempt) { return jdbc.queryForObject("SELECT send_authorized_at IS NOT NULL FROM jobs WHERE id=?",Boolean.class,attempt); }
    public void authorize(String attempt,Instant now) { requireTx();one(jdbc.update("UPDATE jobs SET send_authorized_at=?,revision=revision+1,updated_at=? WHERE id=? AND state='running' AND send_authorized_at IS NULL",Timestamp.from(now),Timestamp.from(now),attempt)); }
    public void refund(String root,String attempt,long token,long revision,Instant eligible,Instant now,String reason) {
        requireTx();one(jdbc.update("UPDATE jobs SET state='queued',call_count=call_count-1,revision=revision+1,eligible_at=?,updated_at=? WHERE id=? AND record_kind='logical_task' AND state='running' AND call_count BETWEEN 1 AND 3",Timestamp.from(eligible),Timestamp.from(now),root));
        one(jdbc.update("UPDATE jobs SET state='prepared',worker_instance_id=NULL,dispatch_token=NULL,send_authorized_at=NULL,request_deadline=NULL,slot_released_at=?,release_reason=?,revision=revision+1,updated_at=? WHERE id=? AND state='running' AND dispatch_token=?",Timestamp.from(now),reason,Timestamp.from(now),attempt,token));
        var slot=slot();if(slot.fencing()>=CanonicalJson.MAX_SAFE_INTEGER||slot.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
        one(jdbc.update("UPDATE worker_lock SET slot_state='free',holder=NULL,job_id=NULL,lease_until=NULL,request_deadline=NULL,occupied_since=NULL,fencing_token=fencing_token+1,slot_revision=slot_revision+1 WHERE id=? AND slot_state='occupied' AND job_id=? AND fencing_token=? AND slot_revision=?",slot.id(),attempt,token,revision));
    }
    public String receipt(String keyHash,String type) {
        var rows=jdbc.query("SELECT target_type,action_payload FROM jobs WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},keyHash);
        if(rows.isEmpty())return null;if(rows.size()!=1||!type.equals(rows.getFirst()[0]))throw conflict();return rows.getFirst()[1];
    }
    public String claimReceipt(String targetHash) {
        var rows=jdbc.query("SELECT action_payload FROM jobs WHERE record_kind='disposition' AND target_type='worker_claim' AND target_key_hash=UNHEX(?)",(rs,n)->rs.getString(1),targetHash);
        if(rows.size()>1)throw conflict();return rows.isEmpty()?null:rows.getFirst();
    }
    private static Instant instant(Timestamp timestamp){return timestamp==null?null:timestamp.toInstant();}
    private static void one(int count){if(count!=1)throw conflict();}
    private static IllegalStateException conflict(){return new IllegalStateException("worker persistence conflict");}
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("worker mutation requires shared writer");}
}
