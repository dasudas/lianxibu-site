package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Native latest intent metadata for report admission. It grants no input, operation, or dispatch authority. */
public final class JdbcLatestReportIntent {
 public record Scope(String logicalKey,String type,LocalDate day){
  public Scope {if(day==null||type==null)throw invalid();String period=switch(type){case "daily_report"->"daily";case "weekly_report"->{if(day.getDayOfWeek()!=DayOfWeek.SUNDAY)throw invalid();yield "weekly";}case "monthly_report"->{if(day.getDayOfMonth()!=day.lengthOfMonth())throw invalid();yield "monthly";}default->throw invalid();};if(!("report:"+period+":"+day).equals(logicalKey))throw invalid();}
 }
 public record Latest(String id,Scope scope,long generation,long revision,String state,String inputState,
  String coverage,Instant cutoff,Instant createdAt,Instant updatedAt){@Override public String toString(){return "LatestNativeReportIntent[redacted]";}}
 private final DataSource source;private final JdbcTemplate sql;
 public JdbcLatestReportIntent(DataSource source){this.source=Objects.requireNonNull(source);sql=new JdbcTemplate(source);}
 public Latest read(Scope scope){transaction(true);return load(Objects.requireNonNull(scope));}
 public Latest applicationRead(Scope scope){transaction(false);return load(Objects.requireNonNull(scope));}
 private Latest load(Scope scope){
  byte[] hash=JdbcGenerationLineage.hash(scope.logicalKey());String initial=JdbcFrozenInputs.stableId("intent",scope.logicalKey()+":1");
  var rows=sql.query("SELECT id,record_kind,task_kind,target_id,business_key,record_identity_hash,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 96 THEN logical_key ELSE NULL END,logical_key_hash,generation,task_type,business_date,revision,state,input_state,coverage_state,input_cutoff_at,created_at,updated_at,eligible_at,call_count,max_attempts,priority_no,"+
   "stage_part,intent_id,root_job_id,attempt_no,snapshot_owner_id,input_hash,owner_job_id,purpose,stamp_id,part_no,action_key_hash,target_type,target_key_json,operator_id,reason,action_payload,request_json,payload_json,result_hash,applied_at,report_mode,dependency_id " +
   "FROM jobs WHERE ((record_kind='generation_intent' OR task_kind='generation_intent') AND (logical_key_hash=? OR logical_key=? OR (task_type=? AND business_date=?))) OR id=? ORDER BY generation DESC,id LIMIT 2",(r,n)->{
    long generation=number(r.getObject(9)),revision=number(r.getObject(12));if(generation<1)throw invalid();
    String id=JdbcFrozenInputs.stableId("intent",scope.logicalKey()+":"+generation);
    var node=JsonMapper.shared().createObjectNode().put("record_kind","generation_intent").put("natural_key",scope.logicalKey()+"\u0000"+generation);
    byte[] identity=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,node));
    String state=r.getString(13),input=r.getString(14),coverage=r.getString(15);
    Instant cutoff=time(r.getTimestamp(16,utc()),true),created=time(r.getTimestamp(17,utc()),false),updated=time(r.getTimestamp(18,utc()),false),eligible=time(r.getTimestamp(19,utc()),false);
    if(!id.equals(r.getString(1))||!"generation_intent".equals(r.getString(2))||!"generation_intent".equals(r.getString(3))||!id.equals(r.getString(4))||!("generation_intent:"+HexFormat.of().formatHex(identity)).equals(r.getString(5))||!Arrays.equals(identity,r.getBytes(6))||!scope.logicalKey().equals(r.getString(7))||!Arrays.equals(hash,r.getBytes(8))||!scope.type().equals(r.getString(10))||!scope.day().toString().equals(r.getString(11))||number(r.getObject(20))!=0||r.getObject(21)!=null||number(r.getObject(22))!=0||updated.isBefore(created)||!eligible.equals(created))throw invalid();
    if(!Set.of("waiting_input","queued","no_input","coverage_only","cancelled").contains(state)||!Set.of("waiting_input","ready","no_input").contains(input))throw invalid();
    if(state.equals("waiting_input")){if(!input.equals("waiting_input")||coverage!=null||cutoff!=null)throw invalid();}
    else if(!state.equals("cancelled")){if(cutoff==null||cutoff.isAfter(updated)||!Set.of("complete","partial","no_input").contains(coverage)||state.equals("queued")!=input.equals("ready")||state.equals("no_input")!=coverage.equals("no_input"))throw invalid();}
    for(int field=23;field<=44;field++)if(r.getObject(field)!=null)throw invalid();
    return new Latest(id,scope,generation,revision,state,input,coverage,cutoff,created,updated);
   },hash,scope.logicalKey(),scope.type(),scope.day().toString(),initial);
  if(rows.isEmpty())return null;if(rows.size()>2||rows.size()==2&&rows.get(0).generation()<=rows.get(1).generation())throw invalid();
  Latest latest=rows.getFirst();Long rootGeneration=sql.queryForObject("SELECT MAX(generation) FROM jobs WHERE record_kind='logical_task' AND (logical_key_hash=? OR logical_key=? OR (task_type=? AND business_date=?))",Long.class,hash,scope.logicalKey(),scope.type(),scope.day().toString());
  if(rootGeneration!=null&&(rootGeneration<1||rootGeneration>latest.generation()))throw invalid();return latest;
 }
 private void transaction(boolean readonly){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=readonly||!Integer.valueOf(readonly?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())||!TransactionSynchronizationManager.hasResource(source))throw invalid();}
 private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
 private static Instant time(java.sql.Timestamp value,boolean nullable){if(value==null){if(nullable)return null;throw invalid();}if(value.getNanos()%1000!=0)throw invalid();return value.toInstant();}
 private static long number(Object value){if(!(value instanceof Number))throw invalid();try{long n=new java.math.BigInteger(value.toString()).longValueExact();if(n<0||n>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return n;}catch(RuntimeException bad){throw invalid();}}
 private static IllegalStateException invalid(){return new IllegalStateException("latest native report intent unavailable");}
 @Override public String toString(){return "JdbcLatestReportIntent[redacted]";}
}
