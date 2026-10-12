package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Cancels only an original, unprepared temporary intent. Never creates execution work. */
public final class OwnerAdHocIntentCancelService {
 private static final String TYPE="job_intent_cancel",VERSION="owner-ad-hoc-intent-cancel-v1";
 private record Command(String id,long expected,String actor,String reason,String hash) { }
 private final JdbcTemplate sql;private final JdbcJobRecords records;private final JdbcOwnerAdHocCreation originals;private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;
 public OwnerAdHocIntentCancelService(DataSource source,PlatformTransactionManager tx,SharedWriteExecutor writes,Clock clock){
  sql=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);originals=new JdbcOwnerAdHocCreation(source);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 /** Null delegates other native record kinds to their existing cancellation provider. */
 public JdbcOwnerJobCancellation.Cancelled cancelIfIntent(Actor actor,String id,long expected,String reason,String requestKey){
  owner(actor);WireValues.id(id,false);WireValues.id(actor.userId(),false);
  if(expected<0||expected>CanonicalJson.MAX_SAFE_INTEGER||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048||requestKey==null||!requestKey.matches("[!-~]{1,128}"))throw new IllegalArgumentException("invalid cancellation command");
  var body=JsonMapper.shared().createObjectNode().put("intent_id",id).put("expected_revision",expected).put("actor",actor.userId()).put("reason",reason);String canonical=JdbcFrozenInputs.canonical(body),key=OwnerJobCancelService.actionKey(requestKey);var command=new Command(id,expected,actor.userId(),reason,JdbcResultState.sha(canonical));
  if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
  var receipt=reads.execute(s->committed(key,command));if(receipt!=null)return receipt;
  var captured=reads.execute(s->{var kind=sql.query("SELECT record_kind,task_type FROM jobs WHERE id=? LIMIT 2",(r,n)->new String[]{r.getString(1),r.getString(2)},id);if(kind.isEmpty())return null;if(kind.size()!=1)throw unavailable();if(!"generation_intent".equals(kind.getFirst()[0])||!"ad_hoc_ai".equals(kind.getFirst()[1]))return null;return originals.originalIntent(id);});
  if(captured==null)return null;
  return writes.execute(new SharedWriteExecutor.ActionContext(key,id,null),k->committed(k,command),context->{
   if(!sql.query("SELECT id FROM jobs WHERE action_key_hash=? LIMIT 1",(r,n)->r.getString(1),JdbcGenerationLineage.hash(key)).isEmpty())throw rejected("identity_conflict");
   originals.verify(captured);
   var rows=sql.query("SELECT state,revision,call_count,max_attempts,priority_no,input_state,snapshot_owner_id,input_cutoff_at,result_hash,applied_at,report_mode FROM jobs WHERE id=? AND record_kind='generation_intent' AND task_type='ad_hoc_ai' LIMIT 2 FOR UPDATE",(r,n)->new Object[]{r.getString(1),r.getObject(2,Long.class),r.getObject(3,Integer.class),r.getObject(4,Integer.class),r.getObject(5,Integer.class),r.getString(6),r.getString(7),r.getTimestamp(8),r.getBytes(9),r.getTimestamp(10),r.getString(11)},id);
   if(rows.size()!=1)throw unavailable();var row=rows.getFirst();
   if(row[1]==null||(long)row[1]!=expected)throw rejected("revision_conflict");
   if(!Integer.valueOf(0).equals(row[2])||row[3]!=null||!Integer.valueOf(0).equals(row[4])||!"waiting_input".equals(row[5])||row[6]!=null||row[7]!=null||row[8]!=null||row[9]!=null||row[10]!=null||!Set.of("waiting_input","cancelled").contains(row[0]))throw rejected("run_frozen");
   String root=captured.command().rootId();var related=sql.query("SELECT id FROM jobs WHERE id=? OR intent_id=? OR root_job_id=? OR snapshot_owner_id=? OR owner_job_id=? OR action_key_hash=? LIMIT 2",(r,n)->r.getString(1),root,id,root,root,root,JdbcGenerationLineage.hash("owner-ad-hoc-prepare:"+id));
   if(!related.isEmpty())throw rejected("run_frozen");
   boolean changed=!"cancelled".equals(row[0]);if(changed&&expected==CanonicalJson.MAX_SAFE_INTEGER)throw rejected("run_frozen");long revision=expected+(changed?1:0);Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
   if(changed&&sql.update(connection->{var statement=connection.prepareStatement("UPDATE jobs SET state='cancelled',revision=revision+1,updated_at=? WHERE id=? AND record_kind='generation_intent' AND task_type='ad_hoc_ai' AND revision=? AND state='waiting_input' AND call_count=0 AND max_attempts IS NULL AND priority_no=0 AND input_state='waiting_input' AND snapshot_owner_id IS NULL AND input_cutoff_at IS NULL AND result_hash IS NULL AND applied_at IS NULL AND report_mode IS NULL");statement.setTimestamp(1,java.sql.Timestamp.from(now),Calendar.getInstance(TimeZone.getTimeZone("UTC")));statement.setString(2,id);statement.setLong(3,expected);return statement;})!=1)throw rejected("revision_conflict");
   var target=JsonMapper.shared().createObjectNode().put("intent_id",id).put("expected_revision",expected);
   var payload=JsonMapper.shared().createObjectNode().put("schema_version",VERSION).put("command_hash",command.hash()).put("acceptance_hash",JdbcResultState.sha(captured.json())).put("intent_id",id).put("revision",revision).put("from_state",(String)row[0]).put("state","cancelled").put("changed",changed);
   records.appendDispositionUtc(new JdbcJobRecords.Disposition(operation(key),key,TYPE,JdbcFrozenInputs.canonical(target),actor.userId(),reason,JdbcFrozenInputs.canonical(payload)),now);
   return Objects.requireNonNull(committed(key,command));
  });
 }
 private JdbcOwnerJobCancellation.Cancelled committed(String key,Command command){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
  var rows=sql.query("SELECT id,record_kind,operator_id,reason,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 16384 THEN target_key_json ELSE NULL END,target_key_hash,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 16384 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,record_identity_hash,state,revision,call_count,max_attempts,priority_no FROM jobs WHERE action_key_hash=? LIMIT 2",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBytes(7),r.getString(8),r.getBytes(9),r.getBytes(10),r.getBytes(11),r.getString(12),r.getObject(13,Long.class),r.getObject(14,Integer.class),r.getObject(15,Integer.class),r.getObject(16,Integer.class)},JdbcGenerationLineage.hash(key));
  if(rows.isEmpty())return null;if(rows.size()!=1)throw unavailable();var row=rows.getFirst();if(!TYPE.equals(row[4]))return null;
  try{
   if(!operation(key).equals(row[0])||!"disposition".equals(row[1])||row[5]==null||row[7]==null||!"applied".equals(row[11])||!Long.valueOf(0).equals(row[12])||!Integer.valueOf(0).equals(row[13])||row[14]!=null||!Integer.valueOf(0).equals(row[15])||!Arrays.equals((byte[])row[9],JdbcGenerationLineage.hash(key)))throw unavailable();
   var identity=JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",key);if(!Arrays.equals((byte[])row[10],HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,identity))))throw unavailable();
   var target=CanonicalJson.parse((String)row[5]);var payload=CanonicalJson.parse((String)row[7]);if(!Arrays.equals((byte[])row[6],JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target)))||!Arrays.equals((byte[])row[8],JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(payload))))throw unavailable();
   WireValues.object(target,Set.of("intent_id","expected_revision"),Set.of("intent_id","expected_revision"));var fields=Set.of("schema_version","command_hash","acceptance_hash","intent_id","revision","from_state","state","changed");WireValues.object(payload,fields,fields);
   if(!VERSION.equals(WireValues.text(payload.get("schema_version")))||!"cancelled".equals(WireValues.text(payload.get("state")))||!payload.get("changed").isBoolean())throw unavailable();WireValues.sha256(payload.get("acceptance_hash"));
   long expected=WireValues.nonnegativeInteger(target.get("expected_revision")),revision=WireValues.nonnegativeInteger(payload.get("revision"));String id=WireValues.id(target.get("intent_id"),false),before=WireValues.text(payload.get("from_state"));boolean changed=payload.get("changed").booleanValue();
   if(!id.equals(WireValues.id(payload.get("intent_id"),false))||revision!=expected+(changed?1:0)||!(changed?"waiting_input":"cancelled").equals(before))throw unavailable();
   if(!command.id().equals(id)||command.expected()!=expected||!command.actor().equals(row[2])||!command.reason().equals(row[3])||!command.hash().equals(WireValues.sha256(payload.get("command_hash"))))throw rejected("identity_conflict");
   return new JdbcOwnerJobCancellation.Cancelled(id,revision,"cancelled",operation(key));
  }catch(JdbcOwnerJobCancellation.Rejected refused){throw refused;}catch(RuntimeException damaged){throw unavailable();}
 }
 private static String operation(String key){return JdbcFrozenInputs.stableId("owner-ad-hoc-intent-cancel",key);}
 private static JdbcOwnerJobCancellation.Rejected rejected(String code){return new JdbcOwnerJobCancellation.Rejected(code);}
 private static IllegalStateException unavailable(){return new IllegalStateException("original temporary cancellation unavailable");}
 private static void owner(Actor actor){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
}
