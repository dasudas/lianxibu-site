package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.*;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Exact original native report preparation receipt. Full INPUT and request recovery is a separate consumer. */
public final class JdbcReportPreparedInputs {
    public static final String ACTOR="report-coordinator";
    public record Receipt(String rootId,String inputHash,String state,Instant recordedAt,String payloadJson){@Override public String toString(){return "OriginalReportPreparationReceipt[redacted]";}}
    public static String root(String logicalKey,long generation){if(logicalKey==null||!logicalKey.matches("report:(daily|weekly|monthly):[0-9]{4}-[0-9]{2}-[0-9]{2}")||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();LocalDate.parse(logicalKey.substring(logicalKey.lastIndexOf(':')+1));return JdbcFrozenInputs.stableId("report-root",JdbcFrozenInputs.stableId("intent",logicalKey+":"+generation));}
    public static String action(String root){WireValues.id(root,false);return "report-prepare:"+root;}
    private final JdbcTemplate sql;
    public JdbcReportPreparedInputs(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public Receipt read(String root){readonly();WireValues.id(root,false);String key=action(root),id=JdbcFrozenInputs.stableId("prepare-action",key);byte[] identity=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",key)));String target=JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",root));
        var rows=sql.query("SELECT id,record_kind,state,revision,call_count,max_attempts,priority_no,record_identity_hash,action_key_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 1024 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,acted_at,created_at,updated_at,eligible_at,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 65536 THEN action_payload ELSE NULL END,action_payload_hash,business_key,task_kind,target_id,root_job_id,intent_id,generation,task_type,logical_key,attempt_no,owner_job_id,purpose,stamp_id,part_no FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2",(r,n)->{
            String raw=r.getString(19);if(raw==null||r.getString(11)==null)throw invalid();raw=JdbcFrozenInputs.canonical(CanonicalJson.parse(raw));String actual=JdbcFrozenInputs.canonical(CanonicalJson.parse(r.getString(11)));Instant at=instant(r.getTimestamp(15,utc()));
            if(!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"applied".equals(r.getString(3))||number(r.getObject(4))!=0||number(r.getObject(5))!=0||r.getObject(6)!=null||number(r.getObject(7))!=0||!Arrays.equals(identity,r.getBytes(8))||!Arrays.equals(hash(key),r.getBytes(9))||!"generation_prepare".equals(r.getString(10))||!target.equals(actual)||!Arrays.equals(hash(actual),r.getBytes(12))||!ACTOR.equals(r.getString(13))||!"Frozen generation input and prepared request".equals(r.getString(14))||!at.equals(instant(r.getTimestamp(16,utc())))||!at.equals(instant(r.getTimestamp(17,utc())))||!at.equals(instant(r.getTimestamp(18,utc())))||!Arrays.equals(hash(raw),r.getBytes(20))||!("disposition:"+HexFormat.of().formatHex(identity)).equals(r.getString(21))||!"disposition".equals(r.getString(22))||!id.equals(r.getString(23)))throw invalid();for(int field=24;field<=33;field++)if(r.getObject(field)!=null)throw invalid();return decode(root,raw,at);
        },id,hash(key),identity);if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();return rows.getFirst();
    }
    private static Receipt decode(String root,String raw,Instant at){
        var body=CanonicalJson.parse(raw);var fields=Set.of("schema_version","command_hash","owner_id","cutoff","coverage_state","missing","input_hash","attempt_id","request_hash","eligible_at","state");WireValues.object(body,fields,fields);
        if(!"generation-prepare-receipt-v1".equals(WireValues.text(body.get("schema_version")))||!root.equals(WireValues.id(body.get("owner_id"),false)))throw invalid();WireValues.sha256(body.get("command_hash"));
        String input=WireValues.sha256(body.get("input_hash")),state=WireValues.text(body.get("state"));if(!Set.of("queued","no_input","coverage_only").contains(state))throw invalid();
        boolean queued=state.equals("queued");if(queued){if(!JdbcFrozenInputs.stableId("attempt",root+":1").equals(WireValues.id(body.get("attempt_id"),false)))throw invalid();WireValues.sha256(body.get("request_hash"));}else if(!body.get("attempt_id").isNull()||!body.get("request_hash").isNull())throw invalid();
        String coverage=WireValues.text(body.get("coverage_state"));if(!Set.of("complete","partial","no_input").contains(coverage)||state.equals("no_input")!=coverage.equals("no_input"))throw invalid();
        Instant cutoff=originalInstant(body.get("cutoff")),eligible=originalInstant(body.get("eligible_at"));if(cutoff.isAfter(at)||eligible.isBefore(at))throw invalid();
        var missing=body.get("missing");if(!missing.isArray()||missing.size()>10000)throw invalid();String prior=null;for(var item:missing){String value=WireValues.text(item);if(value.isBlank()||prior!=null&&prior.compareTo(value)>=0)throw invalid();prior=value;}
        return new Receipt(root,input,state,at,raw);
    }
    private static Instant originalInstant(tools.jackson.databind.JsonNode value){String raw=WireValues.text(value);Instant parsed=Instant.parse(raw);if(!raw.equals(parsed.toString())||parsed.getNano()%1000!=0)throw invalid();return parsed;}
    private static byte[] hash(String value){return JdbcGenerationLineage.hash(value);}
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{long result=new java.math.BigInteger(value.toString()).longValueExact();if(result<0||result>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return result;}catch(RuntimeException bad){throw invalid();}}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static Instant instant(java.sql.Timestamp value){if(value==null||value.getNanos()%1000!=0)throw invalid();return value.toInstant();}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original native report preparation receipt unavailable");}
}
