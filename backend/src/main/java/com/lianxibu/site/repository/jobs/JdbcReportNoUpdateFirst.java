package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Permanent native first observation facts only. The consumer must prove the actual committed no-input preparation. */
public final class JdbcReportNoUpdateFirst {
    public static final String ACTOR="report-no-update-observer",REASON="Report no update first observed after commit";
    private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    public record Fact(String rootId,String logicalKey,long generation,LocalDate day,String inputHash,Instant cutoff,Instant preparedAt,Instant observedAt,String payloadJson){@Override public String toString(){return "ReportNoUpdateFirstFact[redacted]";}}
    public static String action(String root){WireValues.id(root,false);return "report-no-update-first:"+root;}
    public static Fact fact(String root,String key,long generation,LocalDate day,String input,Instant cutoff,Instant prepared,Instant observed){var value=JsonMapper.shared().createObjectNode().put("schema_version","report-no-update-first-v1").put("root_id",root).put("logical_key",key).put("generation",generation).put("business_date",Objects.requireNonNull(day).toString()).put("input_hash",input).put("input_cutoff",MICROS.format(cutoff)).put("prepared_at",MICROS.format(prepared)).put("observed_at",MICROS.format(observed));return decode(root,JdbcFrozenInputs.canonical(value),observed);}
    private final JdbcTemplate sql;
    public JdbcReportNoUpdateFirst(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public Fact read(String root){readonly();return read(root,false);}
    private Fact read(String root,boolean locked){WireValues.id(root,false);String key=action(root),id=JdbcFrozenInputs.stableId("report-no-update-first",key);byte[] identity=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",key)));String target=JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",root));
        var rows=sql.query("SELECT id,record_kind,state,revision,call_count,max_attempts,priority_no,record_identity_hash,action_key_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 1024 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,acted_at,created_at,updated_at,eligible_at,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 8192 THEN action_payload ELSE NULL END,action_payload_hash,business_key,task_kind,target_id,root_job_id,intent_id,generation,task_type,logical_key,attempt_no,owner_job_id,purpose,stamp_id,part_no FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2"+(locked?" FOR UPDATE":""),(r,n)->{
            String raw=r.getString(19);if(raw==null||r.getString(11)==null)throw invalid();raw=JdbcFrozenInputs.canonical(CanonicalJson.parse(raw));String actual=JdbcFrozenInputs.canonical(CanonicalJson.parse(r.getString(11)));Instant at=instant(r.getTimestamp(15,utc()));
            if(!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"applied".equals(r.getString(3))||number(r.getObject(4))!=0||number(r.getObject(5))!=0||r.getObject(6)!=null||number(r.getObject(7))!=0||!Arrays.equals(identity,r.getBytes(8))||!Arrays.equals(hash(key),r.getBytes(9))||!"report_no_update_first".equals(r.getString(10))||!target.equals(actual)||!Arrays.equals(hash(actual),r.getBytes(12))||!ACTOR.equals(r.getString(13))||!REASON.equals(r.getString(14))||!at.equals(instant(r.getTimestamp(16,utc())))||!at.equals(instant(r.getTimestamp(17,utc())))||!at.equals(instant(r.getTimestamp(18,utc())))||!Arrays.equals(hash(raw),r.getBytes(20))||!("disposition:"+HexFormat.of().formatHex(identity)).equals(r.getString(21))||!"disposition".equals(r.getString(22))||!id.equals(r.getString(23)))throw invalid();for(int field=24;field<=33;field++)if(r.getObject(field)!=null)throw invalid();return decode(root,raw,at);
        },id,hash(key),identity);if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();return rows.getFirst();
    }
    public Fact save(Fact proposed){
        JdbcFrozenInputs.requireTx();if(!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();Objects.requireNonNull(proposed);
        if(!proposed.equals(decode(proposed.rootId(),proposed.payloadJson(),proposed.observedAt())))throw invalid();
        var prior=read(proposed.rootId(),true);if(prior!=null){if(!prior.logicalKey().equals(proposed.logicalKey())||prior.generation()!=proposed.generation()||!prior.day().equals(proposed.day())||!prior.inputHash().equals(proposed.inputHash())||!prior.cutoff().equals(proposed.cutoff())||!prior.preparedAt().equals(proposed.preparedAt()))throw invalid();return prior;}
        String key=action(proposed.rootId()),target=JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",proposed.rootId()));
        new JdbcJobRecords(Objects.requireNonNull(sql.getDataSource())).appendDispositionUtc(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("report-no-update-first",key),key,"report_no_update_first",target,ACTOR,REASON,proposed.payloadJson()),proposed.observedAt());return Objects.requireNonNull(read(proposed.rootId(),true));
    }
    private static Fact decode(String root,String raw,Instant at){
        var body=CanonicalJson.parse(raw);var fields=Set.of("schema_version","root_id","logical_key","generation","business_date","input_hash","input_cutoff","prepared_at","observed_at");WireValues.object(body,fields,fields);
        if(!"report-no-update-first-v1".equals(WireValues.text(body.get("schema_version")))||!root.equals(WireValues.id(body.get("root_id"),false)))throw invalid();
        String key=WireValues.text(body.get("logical_key"));long generation=WireValues.nonnegativeInteger(body.get("generation"));LocalDate day=WireValues.businessDate(body.get("business_date"));
        if(!("report:daily:"+day).equals(key)||!JdbcReportPreparedInputs.root(key,generation).equals(root))throw invalid();
        Instant cutoff=WireValues.utcMicroseconds(body.get("input_cutoff")),prepared=WireValues.utcMicroseconds(body.get("prepared_at")),observed=WireValues.utcMicroseconds(body.get("observed_at"));
        if(!at.equals(observed)||prepared.isBefore(cutoff)||observed.isBefore(prepared)||observed.getNano()%1000!=0||cutoff.isBefore(day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()))throw invalid();
        return new Fact(root,key,generation,day,WireValues.sha256(body.get("input_hash")),cutoff,prepared,observed,raw);
    }
    private static byte[] hash(String value){return JdbcGenerationLineage.hash(value);}
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{long result=new java.math.BigInteger(value.toString()).longValueExact();if(result<0||result>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return result;}catch(RuntimeException bad){throw invalid();}}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static Instant instant(java.sql.Timestamp value){if(value==null||value.getNanos()%1000!=0)throw invalid();return value.toInstant();}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native report no update first unavailable");}
}
