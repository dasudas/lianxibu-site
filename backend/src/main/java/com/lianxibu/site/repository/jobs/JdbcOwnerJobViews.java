package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native metadata only. These read views grant no execution or result permission. */
public final class JdbcOwnerJobViews {
    public record Row(String id,String kind,String taskType,String targetType,String state,long revision,int priority,
            Instant eligibleAt,String inputState,String coverageState,Instant cutoff,int callCount,Integer maxAttempts,
            Integer attemptNo,String rootId,String intentId,String snapshotOwnerId,String reportMode,String applicationState,
            Instant releasedAt,String releaseReason,String cost,String missingJson,String usageJson,String ownerId,String purpose){
        @Override public String toString(){return "OwnerJobNativeMetadata[redacted]";}
    }
    public record Page(List<Row> items,long total){public Page{items=List.copyOf(items);}}
    public record Slot(String state,long revision,String occupiedAttemptId){ }
    public record InputReference(boolean present,String hash,long generation,int parts){
        @Override public String toString(){return "OwnerJobInputReference[redacted]";}
    }
    private static final int USAGE_BYTES=16384,MISSING_BYTES=262144,PAGE_BYTES=5*1024*1024;
    private static final Set<String> KINDS=Set.of("generation_intent","logical_task","ai_attempt","snapshot_part","disposition");
    private static final String VISIBLE="j.record_kind IN ('generation_intent','logical_task','ai_attempt','snapshot_part','disposition')"
        +" AND COALESCE(j.task_type,'')<>'source_day_roster' AND COALESCE(j.target_type,'') NOT IN ('source_day_roster','source_day_cohort','source_day_dedup')"
        +" AND NOT EXISTS (SELECT 1 FROM jobs day_owner WHERE day_owner.id=j.owner_job_id AND day_owner.task_type='source_day_roster')";
    private static final String COLUMNS="j.id,j.record_kind,j.task_type,j.target_type,j.state,j.revision,j.priority_no,j.eligible_at,j.input_state,j.coverage_state,j.input_cutoff_at,j.call_count,j.max_attempts,j.attempt_no,j.root_job_id,j.intent_id,j.snapshot_owner_id,j.report_mode,j.application_state,j.slot_released_at,j.release_reason,j.cost_decimal,"
        +"CASE WHEN OCTET_LENGTH(j.missing_inputs_json) BETWEEN 1 AND "+MISSING_BYTES+" THEN j.missing_inputs_json ELSE NULL END,OCTET_LENGTH(j.missing_inputs_json),"
        +"CASE WHEN OCTET_LENGTH(j.usage_json) BETWEEN 1 AND "+USAGE_BYTES+" THEN j.usage_json ELSE NULL END,OCTET_LENGTH(j.usage_json),j.owner_job_id,j.purpose";
    private final JdbcTemplate sql;
    public JdbcOwnerJobViews(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}

    public Row job(String id){
        requireRead();return jobSnapshot(id);
    }
    public Row applicationJob(String id){requireWriter();return jobSnapshot(id);}
    private Row jobSnapshot(String id){
        WireValues.id(id,false);var rows=sql.query("SELECT "+COLUMNS+" FROM jobs j WHERE "+VISIBLE+" AND j.id=?",ROW,id);
        if(rows.size()>1)throw invalid();return rows.isEmpty()||!id.equals(rows.getFirst().id())?null:rows.getFirst();
    }
    public Page page(String kind,String target,long page,int size){
        requireRead();if(page<1||page>CanonicalJson.MAX_SAFE_INTEGER||size<1||size>50||page>Long.MAX_VALUE/size||kind!=null&&!KINDS.contains(kind))throw invalid();
        if(target!=null)text(target,32,false);String filter=VISIBLE;var args=new ArrayList<Object>();if(kind!=null){filter+=" AND j.record_kind=?";args.add(kind);}if(target!=null){filter+=" AND j.target_type=?";args.add(target);}
        Long total=sql.queryForObject("SELECT COUNT(*) FROM jobs j WHERE "+filter,Long.class,args.toArray());if(total==null||total<0||total>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();
        args.add(size);args.add((page-1)*size);var rows=sql.query("SELECT "+COLUMNS+" FROM jobs j WHERE "+filter+" ORDER BY j.created_at DESC,j.id LIMIT ? OFFSET ?",ROW,args.toArray());
        long bytes=0;for(var row:rows){bytes+=row.missingJson()==null?0:row.missingJson().getBytes(StandardCharsets.UTF_8).length;bytes+=row.usageJson()==null?0:row.usageJson().getBytes(StandardCharsets.UTF_8).length;if(bytes>PAGE_BYTES)throw invalid();}return new Page(rows,total);
    }
    public List<Row> attempts(String root){
        requireRead();var original=job(root);if(original==null||!"logical_task".equals(original.kind()))throw invalid();
        var rows=sql.query("SELECT "+COLUMNS+" FROM jobs j WHERE "+VISIBLE+" AND j.record_kind='ai_attempt' AND j.root_job_id=? ORDER BY j.attempt_no,j.id LIMIT 4",ROW,root);
        if(rows.size()>3)throw invalid();int previous=0;for(var row:rows){if(!root.equals(row.rootId())||row.attemptNo()==null||row.attemptNo()<=previous)throw invalid();previous=row.attemptNo();}return List.copyOf(rows);
    }
    public Long intentRevision(String id){requireRead();if(id==null)return null;var row=job(id);if(row==null||!"generation_intent".equals(row.kind()))throw invalid();return row.revision();}
    public List<Row> roots(String intent){
        requireRead();intentRevision(intent);if(intent==null)throw invalid();
        var rows=sql.query("SELECT "+COLUMNS+" FROM jobs j WHERE "+VISIBLE+" AND j.record_kind='logical_task' AND j.intent_id=? ORDER BY j.id LIMIT 10001",ROW,intent);
        if(rows.size()>10000)throw invalid();long bytes=0;for(var row:rows){if(!intent.equals(row.intentId()))throw invalid();bytes+=row.missingJson()==null?0:row.missingJson().getBytes(StandardCharsets.UTF_8).length;bytes+=row.usageJson()==null?0:row.usageJson().getBytes(StandardCharsets.UTF_8).length;if(bytes>PAGE_BYTES)throw invalid();}return List.copyOf(rows);
    }
    /** Size-only SQL precedes archive decoding, including malformed or orphan chunks. */
    public InputReference inputReference(String root){
        requireRead();return inputReferenceSnapshot(root,job(root));
    }
    public InputReference applicationInputReference(String root){requireWriter();return inputReferenceSnapshot(root,jobSnapshot(root));}
    private InputReference inputReferenceSnapshot(String root,Row row){
        if(row==null||!"logical_task".equals(row.kind()))throw invalid();
        var references=sql.query("SELECT id,OCTET_LENGTH(input_snapshot_json),input_hash,generation FROM jobs WHERE id=? AND record_kind='logical_task'",(r,n)->{
            if(!root.equals(r.getString(1)))throw invalid();Long size=r.getObject(2,Long.class),generation=r.getObject(4,Long.class);byte[] hash=r.getBytes(3);
            if(generation==null||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||size==null!=(hash==null)||size!=null&&(size<1||size>65536||hash.length!=32))throw invalid();
            return new InputReference(size!=null,hash==null?null:HexFormat.of().formatHex(hash),generation,0);
        },root);if(references.size()!=1)throw invalid();
        var counts=sql.query("SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(payload_json)),0),COUNT(payload_json) FROM jobs WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='input' AND stamp_id='input'",(r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)},root);
        var count=counts.getFirst();var reference=references.getFirst();if(count[0]>64||count[0]!=count[2]||count[1]>6L*1024*1024||(reference.present()?(count[0]<1||count[1]<1):count[0]!=0))throw invalid();
        return new InputReference(reference.present(),reference.hash(),reference.generation(),(int)count[0]);
    }
    public Slot slot(){
        requireRead();var rows=sql.query("SELECT slot_state,slot_revision,job_id FROM worker_lock WHERE id=1",(r,n)->{
            String state=r.getString(1),attempt=r.getString(3);Long revision=r.getObject(2,Long.class);if(!Set.of("free","occupied").contains(state)||revision==null||revision<0||revision>CanonicalJson.MAX_SAFE_INTEGER||"free".equals(state)!=(attempt==null))throw invalid();if(attempt!=null)WireValues.id(attempt,false);return new Slot(state,revision,attempt);
        });if(rows.size()!=1)throw invalid();return rows.getFirst();
    }
    private static final RowMapper<Row> ROW=(r,n)->{
        String id=r.getString(1),kind=r.getString(2),type=r.getString(3),target=r.getString(4),state=r.getString(5);Long revision=r.getObject(6,Long.class);Integer priority=r.getObject(7,Integer.class),calls=r.getObject(12,Integer.class),maximum=r.getObject(13,Integer.class),number=r.getObject(14,Integer.class);
        WireValues.id(id,false);if(!KINDS.contains(kind)||revision==null||revision<0||revision>CanonicalJson.MAX_SAFE_INTEGER||priority==null||priority<0||priority>14||calls==null||calls<0||calls>3)throw invalid();
        if("logical_task".equals(kind)?!Integer.valueOf(3).equals(maximum)||priority<1:maximum!=null||calls!=0||priority!=0)throw invalid();
        if("ai_attempt".equals(kind)&&(number==null||number<1||number>3||r.getString(15)==null))throw invalid();
        text(type,32,true);text(target,32,true);text(state,32,false);String input=r.getString(9),coverage=r.getString(10),root=r.getString(15),intent=r.getString(16),snapshot=r.getString(17),mode=r.getString(18),application=r.getString(19),reason=r.getString(21),cost=r.getString(22),owner=r.getString(27),purpose=r.getString(28);
        for(String key:new String[]{root,intent,snapshot,owner})if(key!=null)WireValues.id(key,false);text(input,16,true);text(coverage,16,true);text(mode,16,true);text(application,32,true);text(reason,32,true);text(cost,30,true);text(purpose,16,true);
        return new Row(id,kind,type,target,state,revision,priority,instant(r,8),input,coverage,instant(r,11),calls,maximum,number,root,intent,snapshot,mode,application,instant(r,20),reason,cost,bounded(r,23,24,MISSING_BYTES),bounded(r,25,26,USAGE_BYTES),owner,purpose);
    };
    private static String bounded(ResultSet r,int raw,int length,int limit)throws SQLException{
        String text=r.getString(raw);Long bytes=r.getObject(length,Long.class);if(bytes==null){if(text!=null)throw invalid();return null;}if(bytes<1||bytes>limit||text==null||text.getBytes(StandardCharsets.UTF_8).length!=bytes)throw invalid();return text;
    }
    private static Instant instant(ResultSet r,int index)throws SQLException{var value=r.getTimestamp(index,Calendar.getInstance(TimeZone.getTimeZone("UTC")));if(value==null)return null;Instant instant=value.toInstant();if(instant.getNano()%1000!=0)throw invalid();return instant;}
    private static void text(String text,int maximum,boolean nullable){if(text==null){if(!nullable)throw invalid();return;}if(text.isBlank()||text.codePointCount(0,text.length())>maximum||text.codePoints().anyMatch(c->c<32||c==127))throw invalid();}
    private static void requireWriter(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static void requireRead(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native owner job metadata unavailable");}
}
