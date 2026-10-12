package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.JobsService.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Native acceptance only; no AI request, slot reservation or provider call. */
public final class JdbcOwnerAdHocCreation {
    private static final String TYPE="owner_ad_hoc_create",VERSION="owner-ad-hoc-create-v1";
    public static final class Rejected extends RuntimeException {public Rejected(String code){super(code);}}
    public static final class Accepted {
        private final String action,json;private final OwnerAdHocCreateCommand command;private final FrozenGenerationSettings settings;private final Instant cutoff;private final Job response;
        private Accepted(String action,String json,OwnerAdHocCreateCommand command,FrozenGenerationSettings settings,Instant cutoff,Job response){this.action=action;this.json=json;this.command=command;this.settings=settings;this.cutoff=cutoff;this.response=response;}
        public String actionKey(){return action;}public String json(){return json;}public OwnerAdHocCreateCommand command(){return command;}public FrozenGenerationSettings settings(){return settings;}public Instant cutoff(){return cutoff;}public Job response(){return response;}
        @Override public String toString(){return "AcceptedTemporaryRequest[redacted]";}
    }
    private record Row(String id,String kind,String actor,String reason,String type,String target,byte[] targetHash,String payload,byte[] payloadHash,byte[] actionHash,Instant at,Integer calls,Integer maximum,Integer priority,byte[] identity,String state,Long revision){ }
    private final JdbcTemplate sql;private final JdbcJobRecords records;private final JdbcWorkerState worker;
    public JdbcOwnerAdHocCreation(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);worker=new JdbcWorkerState(source);}
    public Accepted committed(String action,OwnerAdHocCreateCommand command){var result=original(action);if(result!=null&&!result.command().hash().equals(command.hash()))throw new Rejected("identity_conflict");return result;}
    public Accepted original(String action){
        transaction();WireValues.id(action,false);
        var rows=sql.query("SELECT id,record_kind,operator_id,reason,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 2048 THEN target_key_json ELSE NULL END,target_key_hash,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 524288 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,acted_at,call_count,max_attempts,priority_no,record_identity_hash,state,revision FROM jobs WHERE action_key_hash=? LIMIT 2",(r,n)->{var at=r.getTimestamp(11,Calendar.getInstance(TimeZone.getTimeZone("UTC")));return new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBytes(7),r.getString(8),r.getBytes(9),r.getBytes(10),at==null?null:at.toInstant(),r.getObject(12,Integer.class),r.getObject(13,Integer.class),r.getObject(14,Integer.class),r.getBytes(15),r.getString(16),r.getObject(17,Long.class));},JdbcGenerationLineage.hash(action));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();var row=rows.getFirst();
        try{
            if(!"applied".equals(row.state())||!Long.valueOf(0).equals(row.revision())||!Arrays.equals(identity("disposition",action),row.identity())||!"disposition".equals(row.kind())||!TYPE.equals(row.type())||!Integer.valueOf(0).equals(row.calls())||row.maximum()!=null||!Integer.valueOf(0).equals(row.priority())||row.target()==null||row.payload()==null||row.at()==null||row.at().getNano()%1000!=0||!Arrays.equals(row.actionHash(),JdbcGenerationLineage.hash(action)))throw invalid();
            var target=CanonicalJson.parse(row.target());var payload=CanonicalJson.parse(row.payload());var fields=Set.of("schema_version","action_key","command_hash","command","settings","cutoff","intent_id","root_id","slot");WireValues.object(payload,fields,fields);
            if(!Arrays.equals(row.targetHash(),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target)))||!Arrays.equals(row.payloadHash(),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(payload)))||!VERSION.equals(WireValues.text(payload.get("schema_version")))||!action.equals(WireValues.id(payload.get("action_key"),false)))throw invalid();
            var command=OwnerAdHocCreateCommand.original(payload.get("command"));if(!operation(command).equals(row.id())||!command.actor().equals(row.actor())||!command.reason().equals(row.reason())||!command.hash().equals(WireValues.sha256(payload.get("command_hash")))||!JdbcFrozenInputs.canonical(target).equals(target(command)))throw invalid();
            var cutoff=WireValues.utcMicroseconds(payload.get("cutoff"));if(!cutoff.equals(row.at())||!command.intentId().equals(WireValues.id(payload.get("intent_id"),false))||!command.rootId().equals(WireValues.id(payload.get("root_id"),false)))throw invalid();
            var settings=FrozenGenerationSettings.read(payload.get("settings"));nativeIntent(command,cutoff);var slot=payload.get("slot");var sf=Set.of("state","revision","attempt_id");WireValues.object(slot,sf,sf);
            String state=WireValues.text(slot.get("state")),attempt=slot.get("attempt_id").isNull()?null:WireValues.id(slot.get("attempt_id"),false);long revision=WireValues.nonnegativeInteger(slot.get("revision"));if(!Set.of("free","occupied").contains(state)||("free".equals(state)!=(attempt==null)))throw invalid();
            return new Accepted(action,JdbcFrozenInputs.canonical(payload),command,settings,cutoff,response(command.intentId(),state,revision,attempt));
        }catch(Rejected refused){throw refused;}catch(RuntimeException damaged){throw invalid();}
    }
    public Accepted accept(String action,OwnerAdHocCreateCommand command,FrozenGenerationSettings settings,Instant now){
        JdbcFrozenInputs.requireTx();Objects.requireNonNull(command);Objects.requireNonNull(settings);if(now==null||now.getNano()%1000!=0)throw invalid();WireValues.id(action,false);
        var targetReceipts=sql.query("SELECT id FROM jobs WHERE target_type=? AND target_key_hash=? LIMIT 2",(r,n)->r.getString(1),TYPE,JdbcGenerationLineage.hash(target(command)));if(!targetReceipts.isEmpty()){if(targetReceipts.size()!=1)throw invalid();var previous=originalById(targetReceipts.getFirst());if(!target(command).equals(target(previous.command())))throw invalid();throw new Rejected("revision_conflict");}
        var existing=sql.query("SELECT id,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,generation,task_type FROM jobs WHERE record_kind='generation_intent' AND logical_key_hash=? LIMIT 2",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getObject(3,Long.class),r.getString(4)},JdbcGenerationLineage.hash(command.logicalKey()));
        if(!existing.isEmpty()){if(existing.size()!=1||!command.intentId().equals(existing.getFirst()[0])||!command.logicalKey().equals(existing.getFirst()[1])||!Long.valueOf(1).equals(existing.getFirst()[2])||!"ad_hoc_ai".equals(existing.getFirst()[3]))throw invalid();var previous=originalById(operation(command));if(!previous.command().logicalKey().equals(command.logicalKey()))throw invalid();throw new Rejected("input_unchanged");}
        var slot=worker.slot();if(slot.id()!=1||slot.revision()<0||slot.revision()>CanonicalJson.MAX_SAFE_INTEGER||slot.fencing()<0||slot.fencing()>CanonicalJson.MAX_SAFE_INTEGER||!Set.of("free","occupied").contains(slot.state())||("free".equals(slot.state())!=(slot.attempt()==null)))throw invalid();if(slot.attempt()!=null)WireValues.id(slot.attempt(),false);
        String intent=records.ensureIntent(new JdbcJobRecords.Intent(command.intentId(),command.logicalKey(),1,"ad_hoc_ai",now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()),now);if(!command.intentId().equals(intent))throw invalid();nativeIntent(command,now);
        var payload=JsonMapper.shared().createObjectNode().put("schema_version",VERSION).put("action_key",action).put("command_hash",command.hash()).put("cutoff",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(now)).put("intent_id",intent).put("root_id",command.rootId());payload.set("command",CanonicalJson.parse(command.json()));payload.set("settings",settings.toJson());var observed=payload.putObject("slot").put("state",slot.state()).put("revision",slot.revision());if(slot.attempt()==null)observed.putNull("attempt_id");else observed.put("attempt_id",slot.attempt());String json=JdbcFrozenInputs.canonical(payload);if(json.getBytes(StandardCharsets.UTF_8).length>524288)throw invalid();
        records.appendDispositionUtc(new JdbcJobRecords.Disposition(operation(command),action,TYPE,target(command),command.actor(),command.reason(),json),now);return Objects.requireNonNull(original(action));
    }
    /** The later short writer must reconstruct and match the entire native acceptance. */
    public Accepted verify(Accepted captured){JdbcFrozenInputs.requireTx();Objects.requireNonNull(captured);var original=original(captured.actionKey());if(original==null||!original.json().equals(captured.json()))throw invalid();return original;}
    private void nativeIntent(OwnerAdHocCreateCommand command,Instant cutoff){
        var rows=sql.query("SELECT record_kind,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,logical_key_hash,generation,task_type,business_date,call_count,max_attempts,priority_no,record_identity_hash FROM jobs WHERE id=? LIMIT 2",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getBytes(3),r.getObject(4,Long.class),r.getString(5),r.getString(6)==null?null:LocalDate.parse(r.getString(6)),r.getObject(7,Integer.class),r.getObject(8,Integer.class),r.getObject(9,Integer.class),r.getBytes(10)},command.intentId());
        if(rows.size()!=1)throw invalid();var row=rows.getFirst();if(!Arrays.equals(identity("generation_intent",command.logicalKey()+"\u0000"+1),(byte[])row[9])||!"generation_intent".equals(row[0])||!command.logicalKey().equals(row[1])||!Arrays.equals(JdbcGenerationLineage.hash(command.logicalKey()),(byte[])row[2])||!Long.valueOf(1).equals(row[3])||!"ad_hoc_ai".equals(row[4])||!cutoff.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().equals(row[5])||!Integer.valueOf(0).equals(row[6])||row[7]!=null||!Integer.valueOf(0).equals(row[8]))throw invalid();
    }
    private static Job response(String intent,String slot,long revision,String attempt){var coverage=new Coverage("partial",List.of(new MissingInput("input_snapshot",intent,null,"not_frozen")),List.of(),List.of());return new Job(intent,"generation_intent",0,"ad_hoc_ai",0,"waiting_input",intent,0L,null,null,"waiting_input",null,null,coverage,null,0,null,0,false,slot,revision,attempt,null,null,null,List.of(),List.of("waiting_input"));}
    private static String target(OwnerAdHocCreateCommand command){return JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","ad_hoc_request").put("id",command.target()).putNull("package_id"));}
    public static String actionKey(String request){if(request==null||!request.matches("[!-~]{1,128}"))throw new IllegalArgumentException("invalid temporary action key");return JdbcFrozenInputs.stableId("owner-job-create",request);}
    /** A bounded intent hint must lead back to the entire original acceptance. */
    public Accepted originalIntent(String intent){
        JdbcSourceDedupResults.requireReadOnly();WireValues.id(intent,false);
        var rows=sql.query("SELECT CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END FROM jobs WHERE id=? AND record_kind='generation_intent' AND task_type='ad_hoc_ai' LIMIT 2",(r,n)->r.getString(1),intent);
        if(rows.isEmpty())return null;if(rows.size()!=1||rows.getFirst()==null)throw invalid();
        var original=originalById(JdbcFrozenInputs.stableId("owner-ad-hoc-create",rows.getFirst()));
        if(!intent.equals(original.command().intentId())||!rows.getFirst().equals(original.command().logicalKey()))throw invalid();return original;
    }
    private Accepted originalById(String id){
        var values=sql.query("SELECT CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 524288 THEN action_payload ELSE NULL END FROM jobs WHERE id=? AND record_kind='disposition' AND target_type=? LIMIT 2",(r,n)->r.getString(1),id,TYPE);
        if(values.size()!=1||values.getFirst()==null)throw invalid();var hint=CanonicalJson.parse(values.getFirst());String action=WireValues.id(hint.get("action_key"),false);var proved=original(action);if(proved==null||!id.equals(operation(proved.command())))throw invalid();return proved;
    }
    private static String operation(OwnerAdHocCreateCommand command){return JdbcFrozenInputs.stableId("owner-ad-hoc-create",command.logicalKey());}
    private static byte[] identity(String kind,String natural){return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind",kind).put("natural_key",natural)));}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original temporary acceptance unavailable");}
}
