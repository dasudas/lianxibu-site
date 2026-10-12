package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Original daily run and actor-scoped receipt, joined to the caller's shared writer. */
public final class JdbcSourceDayRunRegistrations {
    private static final String ACTOR="source-day-register",SCOPE="source-day-run-v1",OPERATION="daily_register";
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate sql;
    private record Source(SourceDayRoster.RawSource metadata,String body){ }
    private record Receipt(String ack,String result,Instant created){ }
    public JdbcSourceDayRunRegistrations(DataSource data){sql=new JdbcTemplate(Objects.requireNonNull(data));}
    public SourceDayRunRegistration.Reservation reserve(SourceDayRunRegistration.Request request,Instant now){
        transaction(true);Objects.requireNonNull(request);var existing=lookup(request);if(existing!=null)return existing;
        new SourceDayRunRegistration.Reservation(request,1,now,request.hash());
        var rows=sql.query("SELECT id,category,revision,config_revision,activation_stage,state,config_valid_until,draft_config_json IS NOT NULL AS draft_present,effective_config_json IS NOT NULL AS effective_present,OCTET_LENGTH(effective_config_json) AS body_bytes,CASE WHEN OCTET_LENGTH(effective_config_json)<=65536 THEN effective_config_json ELSE NULL END AS body FROM sources WHERE id=? FOR UPDATE",(rs,index)->source(rs),request.source().id());
        if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();Source current=rows.getFirst();
        if(!request.source().equals(current.metadata())||current.metadata().configValidUntil()==null||!current.metadata().configValidUntil().isAfter(now))return null;
        Instant expires=Instant.parse(WireValues.text(CanonicalJson.parse(current.body()).get("valid_until")));
        if(!expires.equals(current.metadata().configValidUntil()))throw invalid();if(!expires.isAfter(now))return null;
        Instant start=request.day().atStartOfDay(SHANGHAI).toInstant(),end=request.day().plusDays(1).atStartOfDay(SHANGHAI).toInstant();
        Object[] values={request.runId(),now,0,request.source().id(),"daily",request.day().toString(),1,start,end,null,request.source().configRevision(),current.body(),"production","daily_delta","daily","pending","pending","waiting_input",0,0,0,"{}","[]"};
        sql.update(c->{var ps=c.prepareStatement("INSERT INTO source_runs(id,created_at,revision,source_id,run_kind,business_date,generation,window_start,window_end,base_run_id,config_version,config_snapshot_json,activation_stage,fetch_mode,collection_reason,delivery_state,fetch_state,analysis_state,accepted_count,excluded_count,pending_count,coverage_json,job_refs_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            for(int i=0;i<values.length;i++)if(values[i] instanceof Instant time)ps.setTimestamp(i+1,Timestamp.from(time),utc());else ps.setObject(i+1,values[i]);return ps;});
        String key=action(request);var result=JsonMapper.shared().createObjectNode().put("generation",1).put("request_hash",request.hash()).put("reserved_at",stamp(now)).put("run_id",request.runId());
        sql.update(c->{var ps=c.prepareStatement("INSERT INTO request_receipts(principal,operation,request_key,request_hash,status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,run_id,original_status,current_status,ack_json,result_ref_json,created_at,revision,request_key_raw,business_hash) VALUES(?,?,?,?,200,NULL,NULL,?,'admin_action',?,?,?,?,'applied','applied',?,?,?,0,?,?)");
            Object[] fields={ACTOR,OPERATION,key,bytes(request.hash()),receiptId(request),ACTOR,SCOPE,sha(key),request.runId(),request.metadataJson(),canonical(result),now,key,bytes(request.source().effectiveConfigHash())};
            for(int i=0;i<fields.length;i++)if(fields[i] instanceof Instant time)ps.setTimestamp(i+1,Timestamp.from(time),utc());else ps.setObject(i+1,fields[i]);return ps;});
        var saved=lookup(request);if(saved==null)throw invalid();return saved;
    }
    public SourceDayRunRegistration.Reservation lookup(SourceDayRunRegistration.Request request){
        transaction(false);Objects.requireNonNull(request);String key=action(request);
        var receipts=sql.query("SELECT id,principal,operation,request_key,request_hash,status_code,response_json,expires_at,receipt_kind,actor_id,scope,key_hash,run_id,original_status,current_status,CASE WHEN OCTET_LENGTH(ack_json)<=8192 THEN ack_json ELSE NULL END AS ack_json,CASE WHEN OCTET_LENGTH(result_ref_json)<=2048 THEN result_ref_json ELSE NULL END AS result_ref_json,created_at,revision,request_key_raw,business_hash FROM request_receipts WHERE actor_id=? AND scope=? AND key_hash=? LIMIT 2",(rs,index)->{
            if(!receiptId(request).equals(rs.getString("id"))||!ACTOR.equals(rs.getString("principal"))||!OPERATION.equals(rs.getString("operation"))||!key.equals(rs.getString("request_key"))||!same(rs.getBytes("request_hash"),bytes(request.hash()))||number(rs.getString("status_code"))!=200||rs.getString("response_json")!=null||rs.getTimestamp("expires_at")!=null||!"admin_action".equals(rs.getString("receipt_kind"))||!ACTOR.equals(rs.getString("actor_id"))||!SCOPE.equals(rs.getString("scope"))||!same(rs.getBytes("key_hash"),sha(key))||!request.runId().equals(rs.getString("run_id"))||!"applied".equals(rs.getString("original_status"))||!"applied".equals(rs.getString("current_status"))||number(rs.getString("revision"))!=0||!key.equals(rs.getString("request_key_raw"))||!same(rs.getBytes("business_hash"),bytes(request.source().effectiveConfigHash())))throw invalid();
            return new Receipt(rs.getString("ack_json"),rs.getString("result_ref_json"),instant(rs,"created_at"));
        },ACTOR,SCOPE,sha(key));
        if(receipts.isEmpty()){
            Long orphan=sql.queryForObject("SELECT COUNT(*) FROM source_runs WHERE id=? OR (source_id=? AND run_kind='daily' AND business_date=? AND generation=1)",Long.class,request.runId(),request.source().id(),request.day().toString());
            if(orphan==null||orphan!=0)throw invalid();return null;
        }
        if(receipts.size()!=1)throw invalid();Receipt receipt=receipts.getFirst();if(receipt.ack()==null||receipt.result()==null||!canonical(CanonicalJson.parse(receipt.ack())).equals(canonical(CanonicalJson.parse(request.metadataJson()))))throw invalid();
        JsonNode result=CanonicalJson.parse(receipt.result());closed(result,"generation","request_hash","reserved_at","run_id");
        if(!request.runId().equals(WireValues.text(result.get("run_id")))||!request.hash().equals(WireValues.sha256(result.get("request_hash"))))throw invalid();
        long generation=WireValues.nonnegativeInteger(result.get("generation"));Instant now=WireValues.utcMicroseconds(result.get("reserved_at"));
        var reservation=new SourceDayRunRegistration.Reservation(request,generation,now,request.hash());if(!now.equals(receipt.created()))throw invalid();
        var runs=sql.query("SELECT id,source_id,run_kind,business_date,generation,window_start,window_end,config_version,CASE WHEN OCTET_LENGTH(config_snapshot_json)<=65536 THEN config_snapshot_json ELSE NULL END AS config_snapshot_json,activation_stage,fetch_mode,collection_reason,base_run_id,created_at FROM source_runs WHERE id=? LIMIT 2",(rs,index)->{
            String body=rs.getString("config_snapshot_json");
            if(!request.runId().equals(rs.getString("id"))||!request.source().id().equals(rs.getString("source_id"))||!"daily".equals(rs.getString("run_kind"))||!request.day().toString().equals(rs.getString("business_date"))||number(rs.getString("generation"))!=1||!request.day().atStartOfDay(SHANGHAI).toInstant().equals(instant(rs,"window_start"))||!request.day().plusDays(1).atStartOfDay(SHANGHAI).toInstant().equals(instant(rs,"window_end"))||number(rs.getString("config_version"))!=request.source().configRevision()||body==null||!same(sha(body),bytes(request.source().effectiveConfigHash()))||!"production".equals(rs.getString("activation_stage"))||!"daily_delta".equals(rs.getString("fetch_mode"))||!"daily".equals(rs.getString("collection_reason"))||rs.getString("base_run_id")!=null||!now.equals(instant(rs,"created_at")))throw invalid();return true;
        },request.runId());if(runs.size()!=1)throw invalid();return reservation;
    }
    private static Source source(ResultSet rs)throws SQLException{
        boolean draft=rs.getBoolean("draft_present");if(rs.wasNull())throw invalid();boolean effective=rs.getBoolean("effective_present");if(rs.wasNull())throw invalid();
        String body=rs.getString("body"),hash=null;
        if(effective){long size=number(rs.getString("body_bytes"));if(size>65536)throw new SourceDayRoster.CapacityExceeded();if(body==null||body.getBytes(StandardCharsets.UTF_8).length!=size||size<1)throw invalid();hash=HexFormat.of().formatHex(sha(body));}
        else if(body!=null||rs.getString("body_bytes")!=null)throw invalid();
        return new Source(new SourceDayRoster.RawSource(rs.getString("id"),rs.getString("category"),number(rs.getString("revision")),number(rs.getString("config_revision")),rs.getString("activation_stage"),rs.getString("state"),instant(rs,"config_valid_until"),draft,effective,hash),body);
    }
    public static String action(SourceDayRunRegistration.Request request){var node=JsonMapper.shared().createObjectNode().put("business_date",request.day().toString()).put("source_id",request.source().id());return "source-day-run:"+CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,node);}
    private static String receiptId(SourceDayRunRegistration.Request request){var node=JsonMapper.shared().createObjectNode().put("kind",SCOPE).put("key",action(request));return CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,node);}
    private static void transaction(boolean writer){if(!TransactionSynchronizationManager.isActualTransactionActive()||writer&&TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("source day run transaction required");}
    private static Instant instant(ResultSet rs,String column)throws SQLException{Timestamp value=rs.getTimestamp(column,utc());return value==null?null:value.toInstant();}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static long number(String value){try{if(value==null||!value.matches("[0-9]+"))throw invalid();return new java.math.BigInteger(value).longValueExact();}catch(RuntimeException bad){throw invalid();}}
    private static byte[] bytes(String hash){return HexFormat.of().parseHex(hash);}
    private static byte[] sha(String text){try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static boolean same(byte[] left,byte[] right){return left!=null&&MessageDigest.isEqual(left,right);}
    private static String canonical(JsonNode body){return new String(CanonicalJson.bytes(body),StandardCharsets.UTF_8);}
    private static void closed(JsonNode body,String...fields){var set=Set.of(fields);WireValues.object(body,set,set);}
    private static String stamp(Instant time){return new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(time);}
    private static IllegalStateException invalid(){return new IllegalStateException("original source day run registration unavailable");}
    @Override public String toString(){return "JdbcSourceDayRunRegistrations[redacted]";}
}
