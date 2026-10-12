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

/** Permanent initial owner receipt. Original intent and slot observation are immutable; no report input/send authority. */
public final class JdbcReportInitialActions {
 public static final String TYPE="report_initial_request",VERSION="report-initial-request-v1";
 public record Command(JdbcInitialReportIntent.Scope scope,String actor,String reason){
  public Command{Objects.requireNonNull(scope);WireValues.id(actor,false);if(reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048)throw invalid();CanonicalJson.bytes(JsonMapper.shared().createObjectNode().put("reason",reason));}
  @Override public String toString(){return "OwnerInitialReportCommand[redacted]";}
 }
 public record Accepted(String operationId,JdbcInitialReportIntent.Initial intent,String coverageJson,String slotState,long slotRevision,String occupiedAttemptId){
  public Accepted{if(coverageJson!=null)coverageJson=JdbcFrozenInputs.canonical(CanonicalJson.parse(coverageJson));}
  @Override public String toString(){return "OriginalInitialReportAcceptance[redacted]";}
 }
 public record Original(Command command,Accepted accepted){@Override public String toString(){return "OriginalInitialReportAction[redacted]";}}
 public static final class Conflict extends RuntimeException {public Conflict(){super("identity_conflict");}}
 private final DataSource source;private final JdbcTemplate sql;private final JdbcJobRecords records;
 public JdbcReportInitialActions(DataSource source){this.source=Objects.requireNonNull(source);sql=new JdbcTemplate(source);records=new JdbcJobRecords(source);}
 public static String action(String requestKey){if(requestKey==null||!requestKey.matches("[!-~]{1,128}"))throw invalid();return "report-initial:"+HexFormat.of().formatHex(JdbcGenerationLineage.hash(requestKey));}
 public static String operation(String key){key(key);return JdbcFrozenInputs.stableId("report-initial-operation",key);}
 public static String commandHash(Command command){return CanonicalJson.hash(CanonicalJson.Domain.ACTION,target(Objects.requireNonNull(command)));}
 public Accepted read(String key,Command command){Objects.requireNonNull(command);var result=original(key);if(result==null)return null;if(!command.equals(result.command()))throw new Conflict();return result.accepted();}
 public Original original(String key){
  transaction(false);key(key);String id=operation(key);byte[] identity=identity(key);
  var rows=sql.query("SELECT id,record_kind,task_kind,target_id,business_key,record_identity_hash,action_key_hash,state,revision,call_count,max_attempts,priority_no,target_type,operator_id,reason,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 262144 THEN target_key_json ELSE NULL END,target_key_hash,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 262144 THEN action_payload ELSE NULL END,action_payload_hash,acted_at,created_at,updated_at,eligible_at,"+
   "intent_id,generation,task_type,logical_key,root_job_id,attempt_no,owner_job_id,purpose,stamp_id,part_no,input_hash,request_json,payload_json,stage_part,snapshot_owner_id,result_hash,applied_at FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2",(r,n)->{
    String rawTarget=r.getString(16),raw=r.getString(18);if(rawTarget==null||raw==null)throw invalid();var target=CanonicalJson.parse(rawTarget);var payload=CanonicalJson.parse(raw);
    var fields=Set.of("schema_version","command_hash","operation_id","intent","coverage","slot");WireValues.object(payload,fields,fields);
    var targetFields=Set.of("logical_key","task_type","business_date","mode","actor","reason");WireValues.object(target,targetFields,targetFields);
    if(!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"disposition".equals(r.getString(3))||!id.equals(r.getString(4))||!("disposition:"+HexFormat.of().formatHex(identity)).equals(r.getString(5))||!Arrays.equals(identity,r.getBytes(6))||!Arrays.equals(JdbcGenerationLineage.hash(key),r.getBytes(7))||!"applied".equals(r.getString(8))||number(r.getObject(9))!=0||number(r.getObject(10))!=0||r.getObject(11)!=null||number(r.getObject(12))!=0||!TYPE.equals(r.getString(13))||!Arrays.equals(JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target)),r.getBytes(17))||!Arrays.equals(JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(payload)),r.getBytes(19)))throw invalid();
    Instant at=time(r.getTimestamp(20,utc()));if(!at.equals(time(r.getTimestamp(21,utc())))||!at.equals(time(r.getTimestamp(22,utc())))||!at.equals(time(r.getTimestamp(23,utc()))))throw invalid();for(int field=24;field<=40;field++)if(r.getObject(field)!=null)throw invalid();
    var originalScope=new JdbcInitialReportIntent.Scope(WireValues.text(target.get("logical_key")),WireValues.text(target.get("task_type")),WireValues.businessDate(target.get("business_date")));
    var originalCommand=new Command(originalScope,WireValues.id(target.get("actor"),false),WireValues.text(target.get("reason")));
    if(!"initial".equals(WireValues.text(target.get("mode")))||!originalCommand.actor().equals(r.getString(14))||!originalCommand.reason().equals(r.getString(15))||!VERSION.equals(WireValues.text(payload.get("schema_version")))||!commandHash(originalCommand).equals(WireValues.sha256(payload.get("command_hash"))))throw invalid();
    var node=payload.get("intent");var intentFields=Set.of("id","generation","revision","state","input_state","coverage_state","cutoff","created_at","updated_at");WireValues.object(node,intentFields,intentFields);
    var intent=new JdbcInitialReportIntent.Initial(WireValues.id(node.get("id"),false),originalScope,WireValues.nonnegativeInteger(node.get("generation")),WireValues.nonnegativeInteger(node.get("revision")),WireValues.text(node.get("state")),WireValues.text(node.get("input_state")),nullableText(node,"coverage_state"),nullableTime(node,"cutoff"),WireValues.utcMicroseconds(node.get("created_at")),WireValues.utcMicroseconds(node.get("updated_at")));
    var slot=payload.get("slot");WireValues.object(slot,Set.of("state","revision","attempt_id"),Set.of("state","revision","attempt_id"));
    Accepted accepted=new Accepted(WireValues.id(payload.get("operation_id"),false),intent,payload.get("coverage").isNull()?null:JdbcFrozenInputs.canonical(payload.get("coverage")),WireValues.text(slot.get("state")),WireValues.nonnegativeInteger(slot.get("revision")),nullable(slot,"attempt_id"));
    validate(key,originalCommand,accepted);if(intent.updatedAt().isAfter(at))throw invalid();
    return new Original(originalCommand,accepted);
   },id,JdbcGenerationLineage.hash(key),identity);
  if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();return rows.getFirst();
 }
 public void append(String key,Command command,Accepted accepted,Instant at){
  transaction(true);validate(key,command,accepted);if(at==null||at.getNano()%1000!=0||at.isBefore(accepted.intent().updatedAt()))throw invalid();
  var previous=read(key,command);if(previous!=null){if(!previous.equals(accepted))throw new Conflict();return;}
  if(!accepted.intent().equals(new JdbcInitialReportIntent(source).applicationRead(command.scope())))throw invalid();
  var body=JsonMapper.shared().createObjectNode().put("schema_version",VERSION).put("command_hash",commandHash(command)).put("operation_id",accepted.operationId());
  var value=accepted.intent();var intent=body.putObject("intent").put("id",value.id()).put("generation",value.generation()).put("revision",value.revision()).put("state",value.state()).put("input_state",value.inputState()).put("created_at",format(value.createdAt())).put("updated_at",format(value.updatedAt()));
  if(value.coverage()==null)intent.putNull("coverage_state");else intent.put("coverage_state",value.coverage());if(value.cutoff()==null)intent.putNull("cutoff");else intent.put("cutoff",format(value.cutoff()));
  if(accepted.coverageJson()==null)body.putNull("coverage");else body.set("coverage",CanonicalJson.parse(accepted.coverageJson()));
  var slot=body.putObject("slot").put("state",accepted.slotState()).put("revision",accepted.slotRevision());if(accepted.occupiedAttemptId()==null)slot.putNull("attempt_id");else slot.put("attempt_id",accepted.occupiedAttemptId());
  if(CanonicalJson.bytes(body).length>262144)throw invalid();
  records.appendDispositionUtc(new JdbcJobRecords.Disposition(operation(key),key,TYPE,JdbcFrozenInputs.canonical(target(command)),command.actor(),command.reason(),JdbcFrozenInputs.canonical(body)),at);
  if(!accepted.equals(read(key,command)))throw invalid();
 }
 private static JsonNode target(Command command){return JsonMapper.shared().createObjectNode().put("logical_key",command.scope().logicalKey()).put("task_type",command.scope().type()).put("business_date",command.scope().day().toString()).put("mode","initial").put("actor",command.actor()).put("reason",command.reason());}
 private static void validate(String key,Command command,Accepted value){
  key(key);Objects.requireNonNull(command);Objects.requireNonNull(value);var intent=Objects.requireNonNull(value.intent());
  if(!operation(key).equals(value.operationId())||!command.scope().equals(intent.scope())||intent.generation()!=1||!JdbcFrozenInputs.stableId("intent",command.scope().logicalKey()+":1").equals(intent.id())||intent.revision()<0||intent.revision()>CanonicalJson.MAX_SAFE_INTEGER||intent.createdAt()==null||intent.updatedAt()==null||intent.createdAt().getNano()%1000!=0||intent.updatedAt().getNano()%1000!=0||intent.updatedAt().isBefore(intent.createdAt())||!Set.of("waiting_input","queued","no_input","coverage_only","cancelled").contains(intent.state())||!Set.of("waiting_input","ready","no_input").contains(intent.inputState()))throw invalid();
  if(intent.cutoff()==null){if(!intent.inputState().equals("waiting_input")||!Set.of("waiting_input","cancelled").contains(intent.state())||intent.coverage()!=null||value.coverageJson()!=null)throw invalid();}
  else{if(intent.cutoff().getNano()%1000!=0||intent.cutoff().isAfter(intent.updatedAt())||!Set.of("complete","partial","no_input").contains(intent.coverage())||value.coverageJson()==null||intent.state().equals("waiting_input"))throw invalid();coverage(CanonicalJson.parse(value.coverageJson()),intent.coverage());}
  if(!Set.of("free","occupied").contains(value.slotState())||value.slotRevision()<0||value.slotRevision()>CanonicalJson.MAX_SAFE_INTEGER||value.slotState().equals("free")!=(value.occupiedAttemptId()==null))throw invalid();if(value.occupiedAttemptId()!=null)WireValues.id(value.occupiedAttemptId(),false);
 }
 private static void coverage(JsonNode node,String state){
  var fields=Set.of("state","missing_inputs","missing_dates","no_update_dates");WireValues.object(node,fields,fields);if(!state.equals(WireValues.text(node.get("state"))))throw invalid();var missing=node.get("missing_inputs");if(!missing.isArray()||missing.size()>1000)throw invalid();
  for(var part:missing){var members=Set.of("kind","id","business_date","reason");WireValues.object(part,members,members);if(WireValues.text(part.get("kind")).isBlank()||WireValues.text(part.get("reason")).isBlank())throw invalid();if(!part.get("id").isNull())WireValues.id(part.get("id"),false);if(!part.get("business_date").isNull())WireValues.businessDate(part.get("business_date"));}
  for(String field:List.of("missing_dates","no_update_dates")){var dates=node.get(field);if(!dates.isArray()||dates.size()>366)throw invalid();var seen=new HashSet<LocalDate>();for(var part:dates)if(!seen.add(WireValues.businessDate(part)))throw invalid();}
 }
 private static String format(Instant value){return new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
 private static Instant nullableTime(JsonNode node,String field){return node.get(field).isNull()?null:WireValues.utcMicroseconds(node.get(field));}
 private static String nullableText(JsonNode node,String field){return node.get(field).isNull()?null:WireValues.text(node.get(field));}
 private void transaction(boolean writer){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(source)||writer&&(TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))||!Set.of(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ).contains(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
 private static String nullable(JsonNode node,String field){return node.get(field).isNull()?null:WireValues.id(node.get(field),false);}
 private static byte[] identity(String key){return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",key)));}
 private static long number(Object value){if(!(value instanceof Number))throw invalid();try{long n=new java.math.BigInteger(value.toString()).longValueExact();if(n<0||n>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return n;}catch(RuntimeException bad){throw invalid();}}
 private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
 private static Instant time(java.sql.Timestamp value){if(value==null||value.getNanos()%1000!=0)throw invalid();return value.toInstant();}
 private static void key(String key){if(key==null||!key.matches("report-initial:[0-9a-f]{64}"))throw invalid();}
 private static IllegalStateException invalid(){return new IllegalStateException("original report initial action unavailable");}
 @Override public String toString(){return "JdbcReportInitialActions[redacted]";}
}
