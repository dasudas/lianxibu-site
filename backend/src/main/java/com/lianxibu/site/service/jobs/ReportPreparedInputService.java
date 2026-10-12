package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.FrozenPublicMetadata;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Whole original report INPUT/request recovery. Current source, eligibility, settings and clocks are never consulted. */
public final class ReportPreparedInputService {
 public record Prepared(ReportInputCaptureService.Scope scope,JdbcReportPreparedInputs.Receipt receipt,GenerationFlowService.Frozen input){@Override public String toString(){return "OriginalPreparedReportInput[redacted]";}}
 private final DataSource source;private final JdbcReportPreparedInputs receipts;private final JdbcReportTaskHeaders headers;
 private final JdbcFrozenInputs inputs;private final JdbcTemplate jdbc;private final TransactionTemplate reads;
 public ReportPreparedInputService(DataSource source,PlatformTransactionManager manager){
  this.source=Objects.requireNonNull(source);receipts=new JdbcReportPreparedInputs(source);headers=new JdbcReportTaskHeaders(source);inputs=new JdbcFrozenInputs(source);jdbc=new JdbcTemplate(source);
  reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);reads.setTimeout(1);
 }
 public Prepared find(ReportInputCaptureService.Scope scope){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();return reads.execute(s->read(scope));}
 /** Stable receipt callback: separate RR sees committed originals while the outer RC writer retains its entry lock. */
 public Prepared lookup(ReportInputCaptureService.Scope scope){
  if(!TransactionSynchronizationManager.isActualTransactionActive()||!Set.of(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ).contains(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
  return reads.execute(s->read(scope));
 }
 public Prepared read(ReportInputCaptureService.Scope scope){
  readonly();Objects.requireNonNull(scope);String root=JdbcReportPreparedInputs.root(scope.logicalKey(),scope.generation());var receipt=receipts.read(root);if(receipt==null)return null;
  var header=headers.read(root);if(header==null||!scope.logicalKey().equals(header.logicalKey())||scope.generation()!=header.generation()||scope.stage()!=GenerationFlowService.Stage.valueOf(header.stage().toUpperCase(Locale.ROOT))
   ||!scope.day().equals(header.day())||!receipt.inputHash().equals(header.inputHash())||!receipt.recordedAt().equals(header.createdAt())||header.cutoff()==null)throw invalid();
  headers.guardOriginalInput(root);var snapshot=CanonicalJson.parse(inputs.read(root,receipt.inputHash()));var complete=ReportPreparationInputProfile.complete(snapshot);
  var command=complete.command();if(!scope.logicalKey().equals(command.logicalKey())||scope.generation()!=command.generation()||scope.stage()!=command.stage()||!scope.day().equals(command.businessDate())||!header.cutoff().equals(WireValues.utcMicroseconds(snapshot.get("cutoff"))))throw invalid();
  FrozenPublicMetadata.read(snapshot.get(FrozenPublicMetadata.FIELD),command.applicationContext());
  originalAttempt(root,receipt);
  var frozen=new GenerationJdbcStore(source,Clock.fixed(receipt.recordedAt(),ZoneOffset.UTC),JdbcReportPreparedInputs.ACTOR,root,command).committed(JdbcReportPreparedInputs.action(root));
  if(frozen==null||!receipt.inputHash().equals(frozen.inputHash())||!complete.snapshotJson().equals(frozen.snapshotJson())||!receipt.state().equals(frozen.state())||!header.cutoff().equals(frozen.cutoff()))throw invalid();
  long bytes=bytes(frozen.snapshotJson())+bytes(frozen.requestJson())+bytes(receipt.payloadJson());if(bytes>32L*1024*1024)throw new GenerationFlowService.CapacityGuarded();
  return new Prepared(scope,receipt,frozen);
 }
 private void originalAttempt(String root,JdbcReportPreparedInputs.Receipt receipt){
  String id=JdbcFrozenInputs.stableId("attempt",root+":1");byte[] identity=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("record_kind","ai_attempt").put("natural_key",root+"\u0000"+1)));
  var rows=jdbc.query("SELECT id,record_kind,record_identity_hash,root_job_id,attempt_no,snapshot_owner_id,input_hash,call_count,max_attempts,priority_no,business_key,task_kind,target_id,created_at,"+
   "CASE WHEN OCTET_LENGTH(request_json) BETWEEN 1 AND 1048576 THEN 1 ELSE 0 END,(payload_json IS NULL OR OCTET_LENGTH(payload_json)<=65536),intent_id,generation,task_type,logical_key,owner_job_id,purpose,stamp_id,part_no,action_key_hash,target_type,target_key_json,operator_id,reason,action_payload FROM jobs WHERE id=? OR (root_job_id=? AND attempt_no=1) OR record_identity_hash=? LIMIT 2",(r,n)->{
    if(!id.equals(r.getString(1))||!"ai_attempt".equals(r.getString(2))||!Arrays.equals(identity,r.getBytes(3))||!root.equals(r.getString(4))||r.getLong(5)!=1||!root.equals(r.getString(6))||!Arrays.equals(HexFormat.of().parseHex(receipt.inputHash()),r.getBytes(7))||r.getLong(8)!=0||r.getObject(9)!=null||r.getLong(10)!=0
     ||!("ai_attempt:"+HexFormat.of().formatHex(identity)).equals(r.getString(11))||!"ai_attempt".equals(r.getString(12))||!id.equals(r.getString(13))||r.getTimestamp(14,Calendar.getInstance(TimeZone.getTimeZone("UTC")))==null||!receipt.recordedAt().equals(r.getTimestamp(14,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant())||!r.getBoolean(15)||!r.getBoolean(16))throw invalid();
    for(int field=17;field<=30;field++)if(r.getObject(field)!=null)throw invalid();return id;
   },id,root,identity);
  if("queued".equals(receipt.state())){if(rows.size()!=1)throw invalid();}else if(!rows.isEmpty()||jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt' AND root_job_id=?",Long.class,root)!=0)throw invalid();
 }
 private static long bytes(String text){return text==null?0:text.getBytes(StandardCharsets.UTF_8).length;}
 private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
 private static IllegalStateException invalid(){return new IllegalStateException("whole original prepared report input unavailable");}
 @Override public String toString(){return "ReportPreparedInputService[redacted]";}
}
