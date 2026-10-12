package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded original intent headers and origin metadata. No raw row grants business preparation. */
public final class JdbcSourceIntentScan {
    private static final Set<String> TYPES=Set.of("news_dedup","news_value"),ACTIVE=Set.of("waiting_input","prepared","queued","running");
    public record Candidate(String id,String key,byte[] keyHash,long generation,String type,LocalDate day,String state,long revision){
        public Candidate {keyHash=keyHash.clone();}
        @Override public byte[] keyHash(){return keyHash.clone();}
        @Override public String toString(){return "SourceIntentScanCandidate[redacted]";}
    }
    public record Page(List<Candidate> candidates,boolean hasMore,String nextCursor){
        public Page {candidates=List.copyOf(candidates);}
        @Override public String toString(){return "SourceIntentScanPage[redacted]";}
    }
    public record Origin(String id,String kind,String type,String target,byte[] targetHash,String actor,String reason,String payload,byte[] payloadHash,byte[] actionHash,Instant actedAt,Long targetBytes,Long payloadBytes){
        public Origin {targetHash=copy(targetHash);payloadHash=copy(payloadHash);actionHash=copy(actionHash);}
        @Override public byte[] targetHash(){return copy(targetHash);}@Override public byte[] payloadHash(){return copy(payloadHash);}@Override public byte[] actionHash(){return copy(actionHash);}
        private static byte[] copy(byte[] raw){return raw==null?null:raw.clone();}
        @Override public String toString(){return "SourceIntentOriginMetadata[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcSourceIntentScan(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Page page(String afterId,int limit){
        JdbcSourceDedupResults.requireReadOnly();if(limit<1||limit>50||afterId!=null&&!afterId.matches("[a-z0-9._:-]{1,64}"))throw invalid();
        var rows=jdbc.query("SELECT id,record_kind,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,logical_key_hash,generation,task_type,business_date,state,revision FROM jobs WHERE record_kind='generation_intent' AND task_type IN ('news_dedup','news_value') AND (state IN ('waiting_input','prepared','queued','running') OR (task_type='news_value' AND state IN ('succeeded','no_input')))"+(afterId==null?"":" AND id>?")+" ORDER BY id LIMIT ?",(r,n)->{
            String id=r.getString(1),key=r.getString(3),type=r.getString(6),state=r.getString(8);byte[] hash=r.getBytes(4);Long generation=r.getObject(5,Long.class),revision=r.getObject(9,Long.class);var day=r.getDate(7);
            if(!"generation_intent".equals(r.getString(2))||key==null||key.isBlank()||key.getBytes(StandardCharsets.UTF_8).length>4096||hash==null||hash.length!=32||generation==null||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||day==null||revision==null||revision<0||revision>CanonicalJson.MAX_SAFE_INTEGER||!TYPES.contains(type)||!(ACTIVE.contains(state)||"news_value".equals(type)&&Set.of("succeeded","no_input").contains(state))||!Arrays.equals(hash,JdbcGenerationLineage.hash(key))||!JdbcFrozenInputs.stableId("intent",key+":"+generation).equals(id))throw invalid();return new Candidate(id,key,hash,generation,type,day.toLocalDate(),state,revision);
        },afterId==null?new Object[]{limit+1}:new Object[]{afterId,limit+1});
        boolean more=rows.size()>limit;var selected=more?List.copyOf(rows.subList(0,limit)):List.copyOf(rows);return new Page(selected,more,more?selected.getLast().id():null);
    }
    public List<Origin> origins(String intent,String type){
        JdbcSourceDedupResults.requireReadOnly();if(intent==null||!intent.matches("[A-Za-z0-9._:-]{1,64}")||!TYPES.contains(type))throw invalid();String nativeType="news_dedup".equals(type)?"collection_handoff":"source_value_intent";
        var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",intent);
        return List.copyOf(jdbc.query("SELECT id,record_kind,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 2048 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 8192 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,acted_at,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload) FROM jobs WHERE target_type=? AND target_key_hash=? LIMIT 2",(r,n)->{var at=r.getTimestamp(11);return new Origin(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBytes(5),r.getString(6),r.getString(7),r.getString(8),r.getBytes(9),r.getBytes(10),at==null?null:at.toInstant(),r.getObject(12,Long.class),r.getObject(13,Long.class));},nativeType,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target))));
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original source intent scan unavailable");}
}
