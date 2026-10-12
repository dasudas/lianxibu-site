package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact permanent owner supplement action metadata. Full original INPUT recovery is a separate consumer. */
public final class JdbcReportSupplementActions {
 public static final String TYPE="report_supplement_request",VERSION="report-supplement-request-v1";
 public record Command(JdbcLatestReportIntent.Scope scope,long expectedIntentRevision,String expectedContentId,Long expectedContentRevision,String actor,String reason){
  public Command{Objects.requireNonNull(scope);WireValues.id(actor,false);if(expectedIntentRevision<0||expectedIntentRevision>CanonicalJson.MAX_SAFE_INTEGER||(expectedContentId==null)!=(expectedContentRevision==null)||expectedContentRevision!=null&&(expectedContentRevision<1||expectedContentRevision>=CanonicalJson.MAX_SAFE_INTEGER)||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048)throw invalid();if(expectedContentId!=null)WireValues.id(expectedContentId,false);CanonicalJson.bytes(JsonMapper.shared().createObjectNode().put("reason",reason));}
  @Override public String toString(){return "OwnerReportSupplementCommand[redacted]";}
 }
 public record Accepted(String operationId,String rootId,String intentId,long generation,long intentRevision,String contentId,Long baseRevision,Instant cutoff,String inputHash,String businessInputHash,String state,String originalReceiptHash,String slotState,long slotRevision,String occupiedAttemptId){
  @Override public String toString(){return "OriginalReportSupplementAcceptance[redacted]";}
 }
 public record Original(Command command,Accepted accepted){@Override public String toString(){return "OriginalReportSupplementAction[redacted]";}}
 public static final class Conflict extends RuntimeException {public Conflict(){super("identity_conflict");}}
 private final DataSource source;private final JdbcTemplate sql;private final JdbcJobRecords records;
 public JdbcReportSupplementActions(DataSource source){this.source=Objects.requireNonNull(source);sql=new JdbcTemplate(source);records=new JdbcJobRecords(source);}
 public static String action(String requestKey){if(requestKey==null||!requestKey.matches("[!-~]{1,128}"))throw invalid();return "report-supplement:"+HexFormat.of().formatHex(JdbcGenerationLineage.hash(requestKey));}
 public static String operation(String key){key(key);return JdbcFrozenInputs.stableId("report-supplement-operation",key);}
 public static String commandHash(Command command){return CanonicalJson.hash(CanonicalJson.Domain.ACTION,target(Objects.requireNonNull(command)));}
 public Accepted read(String key,Command command){Objects.requireNonNull(command);var result=original(key);if(result==null)return null;if(!command.equals(result.command()))throw new Conflict();return result.accepted();}
 public Original original(String key){
  transaction(false);key(key);String id=operation(key);byte[] identity=identity(key);
  var rows=sql.query("SELECT id,record_kind,task_kind,target_id,business_key,record_identity_hash,action_key_hash,state,revision,call_count,max_attempts,priority_no,target_type,operator_id,reason,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 16384 THEN target_key_json ELSE NULL END,target_key_hash,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 16384 THEN action_payload ELSE NULL END,action_payload_hash,acted_at,created_at,updated_at,eligible_at,"+
   "intent_id,generation,task_type,logical_key,root_job_id,attempt_no,owner_job_id,purpose,stamp_id,part_no,input_hash,request_json,payload_json,stage_part,snapshot_owner_id,result_hash,applied_at FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2",(r,n)->{
    String rawTarget=r.getString(16),raw=r.getString(18);if(rawTarget==null||raw==null)throw invalid();var target=CanonicalJson.parse(rawTarget);var payload=CanonicalJson.parse(raw);
    var fields=Set.of("schema_version","command_hash","operation_id","root_id","intent_id","prior_intent_id","prior_generation","generation","intent_revision","content_id","base_revision","cutoff","input_hash","business_input_hash","state","original_receipt_hash","slot");WireValues.object(payload,fields,fields);
    var targetFields=Set.of("logical_key","task_type","business_date","expected_intent_revision","expected_content_id","expected_content_revision","actor","reason");WireValues.object(target,targetFields,targetFields);
    if(!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"disposition".equals(r.getString(3))||!id.equals(r.getString(4))||!("disposition:"+HexFormat.of().formatHex(identity)).equals(r.getString(5))||!Arrays.equals(identity,r.getBytes(6))||!Arrays.equals(JdbcGenerationLineage.hash(key),r.getBytes(7))||!"applied".equals(r.getString(8))||number(r.getObject(9))!=0||number(r.getObject(10))!=0||r.getObject(11)!=null||number(r.getObject(12))!=0||!TYPE.equals(r.getString(13))||!Arrays.equals(JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target)),r.getBytes(17))||!Arrays.equals(JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(payload)),r.getBytes(19)))throw invalid();
    Instant at=time(r.getTimestamp(20,utc()));if(!at.equals(time(r.getTimestamp(21,utc())))||!at.equals(time(r.getTimestamp(22,utc())))||!at.equals(time(r.getTimestamp(23,utc()))))throw invalid();for(int field=24;field<=40;field++)if(r.getObject(field)!=null)throw invalid();
    var originalScope=new JdbcLatestReportIntent.Scope(WireValues.text(target.get("logical_key")),WireValues.text(target.get("task_type")),WireValues.businessDate(target.get("business_date")));
    var originalCommand=new Command(originalScope,WireValues.nonnegativeInteger(target.get("expected_intent_revision")),nullable(target,"expected_content_id"),target.get("expected_content_revision").isNull()?null:WireValues.nonnegativeInteger(target.get("expected_content_revision")),WireValues.id(target.get("actor"),false),WireValues.text(target.get("reason")));
    if(!originalCommand.actor().equals(r.getString(14))||!originalCommand.reason().equals(r.getString(15))||!VERSION.equals(WireValues.text(payload.get("schema_version")))||!commandHash(originalCommand).equals(WireValues.sha256(payload.get("command_hash"))))throw invalid();
    long generation=WireValues.nonnegativeInteger(payload.get("generation")),prior=WireValues.nonnegativeInteger(payload.get("prior_generation"));if(generation<2||prior!=generation-1||!JdbcFrozenInputs.stableId("intent",originalScope.logicalKey()+":"+prior).equals(WireValues.id(payload.get("prior_intent_id"),false)))throw invalid();
    var slot=payload.get("slot");WireValues.object(slot,Set.of("state","revision","attempt_id"),Set.of("state","revision","attempt_id"));
    Accepted accepted=new Accepted(WireValues.id(payload.get("operation_id"),false),WireValues.id(payload.get("root_id"),false),WireValues.id(payload.get("intent_id"),false),generation,WireValues.nonnegativeInteger(payload.get("intent_revision")),WireValues.id(payload.get("content_id"),false),payload.get("base_revision").isNull()?null:WireValues.nonnegativeInteger(payload.get("base_revision")),WireValues.utcMicroseconds(payload.get("cutoff")),WireValues.sha256(payload.get("input_hash")),WireValues.sha256(payload.get("business_input_hash")),WireValues.text(payload.get("state")),WireValues.sha256(payload.get("original_receipt_hash")),WireValues.text(slot.get("state")),WireValues.nonnegativeInteger(slot.get("revision")),nullable(slot,"attempt_id"));
    validate(key,originalCommand,accepted);if(accepted.cutoff().isAfter(at))throw invalid();
    return new Original(originalCommand,accepted);
   },id,JdbcGenerationLineage.hash(key),identity);
  if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();return rows.getFirst();
 }
 public void append(String key,Command command,Accepted accepted,Instant at){
  transaction(true);validate(key,command,accepted);if(at==null||at.getNano()%1000!=0||at.isBefore(accepted.cutoff()))throw invalid();
  var previous=read(key,command);if(previous!=null){if(!previous.equals(accepted))throw new Conflict();return;}
  var body=JsonMapper.shared().createObjectNode().put("schema_version",VERSION).put("command_hash",commandHash(command)).put("operation_id",accepted.operationId()).put("root_id",accepted.rootId()).put("intent_id",accepted.intentId()).put("prior_intent_id",JdbcFrozenInputs.stableId("intent",command.scope().logicalKey()+":"+(accepted.generation()-1))).put("prior_generation",accepted.generation()-1).put("generation",accepted.generation()).put("intent_revision",accepted.intentRevision()).put("content_id",accepted.contentId());
  if(accepted.baseRevision()==null)body.putNull("base_revision");else body.put("base_revision",accepted.baseRevision());body.put("cutoff",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(accepted.cutoff())).put("input_hash",accepted.inputHash()).put("business_input_hash",accepted.businessInputHash()).put("state",accepted.state()).put("original_receipt_hash",accepted.originalReceiptHash());
  var slot=body.putObject("slot").put("state",accepted.slotState()).put("revision",accepted.slotRevision());if(accepted.occupiedAttemptId()==null)slot.putNull("attempt_id");else slot.put("attempt_id",accepted.occupiedAttemptId());
  records.appendDispositionUtc(new JdbcJobRecords.Disposition(operation(key),key,TYPE,JdbcFrozenInputs.canonical(target(command)),command.actor(),command.reason(),JdbcFrozenInputs.canonical(body)),at);
  if(!accepted.equals(read(key,command)))throw invalid();
 }
 private static JsonNode target(Command command){var value=JsonMapper.shared().createObjectNode().put("logical_key",command.scope().logicalKey()).put("task_type",command.scope().type()).put("business_date",command.scope().day().toString()).put("expected_intent_revision",command.expectedIntentRevision()).put("actor",command.actor()).put("reason",command.reason());if(command.expectedContentId()==null)value.putNull("expected_content_id");else value.put("expected_content_id",command.expectedContentId());if(command.expectedContentRevision()==null)value.putNull("expected_content_revision");else value.put("expected_content_revision",command.expectedContentRevision());return value;}
 private static void validate(String key,Command command,Accepted value){key(key);Objects.requireNonNull(command);Objects.requireNonNull(value);String logical=command.scope().logicalKey();String content=JdbcFrozenInputs.stableId("report-content",logical);if(!operation(key).equals(value.operationId())||value.intentRevision()!=command.expectedIntentRevision()+1||value.intentRevision()<1||value.intentRevision()>CanonicalJson.MAX_SAFE_INTEGER||value.generation()<2||value.generation()>CanonicalJson.MAX_SAFE_INTEGER||!JdbcReportPreparedInputs.root(logical,value.generation()).equals(value.rootId())||!JdbcFrozenInputs.stableId("intent",logical+":"+value.generation()).equals(value.intentId())||!content.equals(value.contentId())||!Objects.equals(command.expectedContentRevision(),value.baseRevision())||command.expectedContentId()!=null&&!content.equals(command.expectedContentId())||value.cutoff()==null||value.cutoff().getNano()%1000!=0||value.cutoff().isBefore(command.scope().day().plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant())||!Set.of("queued","no_input","coverage_only").contains(value.state()))throw invalid();if(!Set.of("free","occupied").contains(value.slotState())||value.slotRevision()<0||value.slotRevision()>CanonicalJson.MAX_SAFE_INTEGER||value.slotState().equals("free")!=(value.occupiedAttemptId()==null))throw invalid();if(value.occupiedAttemptId()!=null)WireValues.id(value.occupiedAttemptId(),false);for(String hash:List.of(value.inputHash(),value.businessInputHash(),value.originalReceiptHash()))if(hash==null||!hash.matches("[0-9a-f]{64}"))throw invalid();}
 private void transaction(boolean writer){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(source)||writer&&(TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))||!Set.of(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ).contains(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
 private static String nullable(JsonNode node,String field){return node.get(field).isNull()?null:WireValues.id(node.get(field),false);}
 private static byte[] identity(String key){return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",key)));}
 private static long number(Object value){if(!(value instanceof Number))throw invalid();try{long n=new java.math.BigInteger(value.toString()).longValueExact();if(n<0||n>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return n;}catch(RuntimeException bad){throw invalid();}}
 private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
 private static Instant time(java.sql.Timestamp value){if(value==null||value.getNanos()%1000!=0)throw invalid();return value.toInstant();}
 private static void key(String key){if(key==null||!key.matches("report-supplement:[0-9a-f]{64}"))throw invalid();}
 private static IllegalStateException invalid(){return new IllegalStateException("original report supplementary action unavailable");}
 @Override public String toString(){return "JdbcReportSupplementActions[redacted]";}
}
