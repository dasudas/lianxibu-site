package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.sql.Timestamp;import java.time.Instant;import java.util.*;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Recovery updates for physical jobs and the existing sole slot. No provider I/O. */
public final class JdbcWorkerRecovery {
 public record Attempt(String root,String id,long token,long slotRevision,Instant lease,Instant started,
                       String owner,String rootState,String attemptState,long revision,int calls){}
 private final JdbcTemplate jdbc;private final JdbcWorkerState worker;
 public JdbcWorkerRecovery(DataSource source,JdbcWorkerState worker){jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.worker=Objects.requireNonNull(worker);}
 public Attempt attempt(String id){
  var rows=jdbc.query("SELECT a.root_job_id,a.id,a.dispatch_token,a.worker_instance_id,r.state,a.state,r.revision,r.call_count FROM jobs a JOIN jobs r ON r.id=a.root_job_id WHERE a.id=? AND a.record_kind='ai_attempt' AND r.record_kind='logical_task'",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getObject(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getInt(8)},id);
  if(rows.isEmpty())return null;var row=rows.getFirst();if(row[2]==null||row[3]==null)return null;long token=((Number)row[2]).longValue();
  var receipts=jdbc.query("SELECT action_payload FROM jobs WHERE record_kind='disposition' AND target_type='worker_claim' AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.root_id'))=? AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.attempt_id'))=? AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.dispatch_token'))=?",(rs,n)->rs.getString(1),row[0],id,Long.toString(token));
  if(receipts.size()!=1)throw conflict();var claim=CanonicalJson.parse(receipts.getFirst());if(!row[3].equals(claim.path("owner_epoch").asText())||token!=claim.path("fencing").asLong())throw conflict();
  long slotRevision=claim.path("slot_revision").asLong();Instant lease=Instant.parse(claim.path("lease_until").asText());var slot=worker.slot();if(id.equals(slot.attempt())&&token==slot.fencing()&&slotRevision==slot.revision()&&row[3].equals(slot.holder())&&slot.lease()!=null)lease=slot.lease();
  return new Attempt((String)row[0],id,token,slotRevision,lease,Instant.parse(claim.path("started_at").asText()),(String)row[3],(String)row[4],(String)row[5],(Long)row[6],(Integer)row[7]);
 }
 public void unknown(Attempt value,String reason,Instant now){
  requireTx();if("unknown_external".equals(value.attemptState()))return;
  one(jdbc.update("UPDATE jobs SET state='unknown_external',revision=revision+1,error_code='unknown_external',updated_at=? WHERE id=? AND record_kind='logical_task' AND revision=? AND state IN ('running','cancel_requested') AND applied_at IS NULL AND call_count=?",Timestamp.from(now),value.root(),value.revision(),value.calls()));
  one(jdbc.update("UPDATE jobs SET state='unknown_external',release_reason=?,slot_released_at=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='ai_attempt' AND state='running' AND dispatch_token=? AND worker_instance_id=? AND root_job_id=?",reason,Timestamp.from(now),Timestamp.from(now),value.id(),value.token(),value.owner(),value.root()));
 }
 public void close(Attempt value,long expected,Instant now){requireTx();one(jdbc.update("UPDATE jobs SET state='blocked',error_code='closed_unresolved',revision=revision+1,updated_at=? WHERE id=? AND record_kind='logical_task' AND state='unknown_external' AND revision=? AND applied_at IS NULL AND call_count=?",Timestamp.from(now),value.root(),expected,value.calls()));}
 public boolean release(Attempt value){
  requireTx();var slot=worker.slot();boolean matching="occupied".equals(slot.state())&&value.id().equals(slot.attempt())&&value.owner().equals(slot.holder())&&slot.fencing()==value.token()&&slot.revision()==value.slotRevision();if(!matching)return false;
  if(slot.fencing()>=CanonicalJson.MAX_SAFE_INTEGER||slot.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
  one(jdbc.update("UPDATE worker_lock SET slot_state='free',holder=NULL,job_id=NULL,lease_until=NULL,request_deadline=NULL,occupied_since=NULL,fencing_token=fencing_token+1,slot_revision=slot_revision+1 WHERE id=? AND slot_state='occupied' AND holder=? AND job_id=? AND fencing_token=? AND slot_revision=?",slot.id(),value.owner(),value.id(),value.token(),value.slotRevision()));return true;
 }
 public void late(Attempt value,String provider,String usageJson,Instant now){
  requireTx();var rows=jdbc.query("SELECT provider_request_id,usage_json FROM jobs WHERE id=? AND record_kind='ai_attempt' AND state='unknown_external' AND root_job_id=? AND dispatch_token=? AND worker_instance_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},value.id(),value.root(),value.token(),value.owner());if(rows.size()!=1)throw conflict();var previous=rows.getFirst();
  if(provider!=null&&previous[0]!=null&&!provider.equals(previous[0]))throw conflict();
  var incoming=CanonicalJson.parse(usageJson);var merged=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();var old=previous[1]==null?null:CanonicalJson.parse(previous[1]);
  boolean known=false;for(String field:List.of("prompt_tokens","completion_tokens")){var prior=old==null?null:old.get(field);var next=incoming.get(field);boolean priorKnown=prior!=null&&!prior.isNull(),nextKnown=next!=null&&!next.isNull();if(priorKnown&&nextKnown&&!prior.equals(next))throw conflict();if(priorKnown){merged.set(field,prior);known=true;}else if(nextKnown){merged.set(field,next);known=true;}else merged.putNull(field);}
  merged.put("state",known?"reported_late":"unknown");String usage=new String(CanonicalJson.bytes(merged),java.nio.charset.StandardCharsets.UTF_8);
  one(jdbc.update("UPDATE jobs SET provider_request_id=COALESCE(provider_request_id,?),usage_json=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='ai_attempt' AND state='unknown_external' AND root_job_id=? AND dispatch_token=? AND worker_instance_id=?",provider,usage,Timestamp.from(now),value.id(),value.root(),value.token(),value.owner()));
 }
 private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("recovery requires shared writer");}
 private static void one(int count){if(count!=1)throw conflict();}
 private static IllegalStateException conflict(){return new IllegalStateException("worker recovery persistence conflict");}
}
