package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Native report root facts only. A header grants no original-body or cutoff permission. */
public final class JdbcReportTaskHeaders {
    public record Header(String id,String intent,String logicalKey,long generation,String stage,LocalDate day,
                         String state,long revision,int calls,String inputState,String inputHash,Instant cutoff,
                         String application,String resultHash,Instant appliedAt,Instant createdAt,Instant updatedAt) {
        @Override public String toString(){return "ReportTaskHeader[redacted]";}
    }
    private static final Set<String> STATES=Set.of("waiting_input","prepared","queued","running","waiting_retry",
        "cancel_requested","unknown_external","blocked","blocked_configuration","result_conflict","succeeded",
        "failed","cancelled","discarded","superseded","no_input","coverage_only","no_updates");
    private static final Set<String> APPLICATIONS=Set.of("APPLIED","CONTENT_REVISION_CONFLICT","SUPERSEDED","INVALID_EVIDENCE","DISCARDED");
    private final JdbcTemplate jdbc;
    public JdbcReportTaskHeaders(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Header read(String root){
        readonly();WireValues.id(root,false);
        var rows=jdbc.query("SELECT id,record_kind,record_identity_hash,intent_id,"+
            "CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,"+
            "logical_key_hash,generation,stage_part,task_type,business_date,state,revision,call_count,max_attempts,"+
            "priority_no,input_state,input_hash,input_cutoff_at,application_state,result_hash,applied_at,created_at,updated_at,"+
            "business_key,task_kind,target_id,"+
            "(input_snapshot_json IS NULL OR OCTET_LENGTH(input_snapshot_json) BETWEEN 1 AND 65536),"+
            "(result_json IS NULL OR OCTET_LENGTH(result_json) BETWEEN 1 AND 16384),"+
            "root_job_id,attempt_no,owner_job_id,purpose,stamp_id,part_no,action_key_hash,target_type,target_key_json,"+
            "operator_id,reason,acted_at,action_payload,request_json,payload_json,snapshot_owner_id"+
            " FROM jobs WHERE id=? LIMIT 2",(r,n)->{
                String key=r.getString(5),stage=r.getString(9),state=r.getString(11),inputState=r.getString(16),application=r.getString(19);
                Long generation=r.getObject(7,Long.class),part=r.getObject(8,Long.class),revision=r.getObject(12,Long.class);
                Integer calls=r.getObject(13,Integer.class),maximum=r.getObject(14,Integer.class),priority=r.getObject(15,Integer.class);
                int expectedPriority=switch(stage==null?"":stage){case "daily_report"->11;case "weekly_report"->12;case "monthly_report"->13;default->throw invalid();};
                if(!root.equals(r.getString(1))||!"logical_task".equals(r.getString(2))||key==null||key.isBlank()
                    ||generation==null||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||part==null||part!=0
                    ||revision==null||revision<0||revision>CanonicalJson.MAX_SAFE_INTEGER||calls==null||calls<0||calls>3
                    ||maximum==null||maximum!=3||priority==null||priority!=expectedPriority||state==null||!STATES.contains(state)
                    ||inputState!=null&&!Set.of("waiting_input","ready","no_input").contains(inputState)
                    ||application!=null&&!APPLICATIONS.contains(application)||!r.getBoolean(27)||!r.getBoolean(28))throw invalid();
                var identity=JsonMapper.shared().createObjectNode().put("record_kind","logical_task").put("natural_key",key+"\u0000"+generation+"\u0000"+part);
                byte[] digest=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,identity));
                if(!Arrays.equals(digest,r.getBytes(3))||!Arrays.equals(JdbcGenerationLineage.hash(key),r.getBytes(6))
                    ||!JdbcFrozenInputs.stableId("intent",key+":"+generation).equals(r.getString(4))
                    ||!("logical_task:"+HexFormat.of().formatHex(digest)).equals(r.getString(24))
                    ||!"logical_task".equals(r.getString(25))||!root.equals(r.getString(26)))throw invalid();
                for(int field=29;field<=44;field++)if(r.getObject(field)!=null)throw invalid();
                String date=r.getString(10);if(date==null)throw invalid();LocalDate day=LocalDate.parse(date);
                if(!date.equals(day.toString()))throw invalid();
                Instant created=time(r.getTimestamp(22,utc())),updated=time(r.getTimestamp(23,utc()));
                if(created==null||updated==null||updated.isBefore(created))throw invalid();
                return new Header(root,r.getString(4),key,generation,stage,day,state,revision,calls,inputState,
                    hash(r.getBytes(17)),time(r.getTimestamp(18,utc())),application,hash(r.getBytes(20)),
                    time(r.getTimestamp(21,utc())),created,updated);
            },root);
        if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    /** Metadata-only guard before the complete original INPUT decoder allocates payloads. */
    public void guardOriginalInput(String root){
        readonly();WireValues.id(root,false);
        var size=jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(payload_json)),0),COALESCE(MAX(OCTET_LENGTH(payload_json)),0),SUM(CASE WHEN payload_json IS NULL THEN 1 ELSE 0 END) FROM jobs WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='input' AND stamp_id='input'",(r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4)},root);
        if(size==null||size[0]<1||size[0]>342||size[1]<1||size[1]>48L*1024*1024||size[2]>132L*1024||size[3]!=0)throw invalid();
    }
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static String hash(byte[] value){if(value==null)return null;if(value.length!=32)throw invalid();return HexFormat.of().formatHex(value);}
    private static Instant time(java.sql.Timestamp value){if(value==null)return null;Instant time=value.toInstant();if(time.getNano()%1000!=0)throw invalid();return time;}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()
        ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native original report header unavailable");}
    @Override public String toString(){return "JdbcReportTaskHeaders[redacted]";}
}
