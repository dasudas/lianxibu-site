package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded original intent headers and origin metadata. No raw row grants business preparation. */
public final class JdbcCatalogSourceIntentScan {
    private static final Set<String> ACTIVE=Set.of("waiting_input","prepared","queued","running","succeeded","no_input");
    private static final String COLUMNS="id,record_kind,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,logical_key_hash,generation,task_type,business_date,state,revision";
    private final JdbcTemplate jdbc;private final String type;
    public JdbcCatalogSourceIntentScan(DataSource source,String category){
        Objects.requireNonNull(source);type=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category+"_dedup";default->throw invalid();};jdbc=new JdbcTemplate(source);
    }
    public JdbcSourceIntentScan.Page page(String afterId,int limit){
        JdbcSourceDedupResults.requireReadOnly();if(limit<1||limit>50||afterId!=null&&!afterId.matches("[a-z0-9._:-]{1,64}"))throw invalid();
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM jobs WHERE record_kind='generation_intent' AND task_type=? AND state IN ('waiting_input','prepared','queued','running','succeeded','no_input')"+(afterId==null?"":" AND id>?")+" ORDER BY id LIMIT ?",(r,n)->header(r),afterId==null?new Object[]{type,limit+1}:new Object[]{type,afterId,limit+1});
        boolean more=rows.size()>limit;var selected=more?List.copyOf(rows.subList(0,limit)):List.copyOf(rows);return new JdbcSourceIntentScan.Page(selected,more,more?selected.getLast().id():null);
    }
    /** Reads one actual original intent header, independent of any paged route supplied by a caller. */
    public JdbcSourceIntentScan.Candidate find(String id){
        JdbcSourceDedupResults.requireReadOnly();if(id==null||!id.matches("[A-Za-z0-9._:-]{1,64}"))throw invalid();
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM jobs WHERE id=? LIMIT 2",(r,n)->header(r),id);
        if(rows.size()>1)throw invalid();if(rows.isEmpty())return null;var row=rows.getFirst();if(!id.equals(row.id()))throw invalid();return row;
    }
    private JdbcSourceIntentScan.Candidate header(java.sql.ResultSet r)throws java.sql.SQLException {

            String id=r.getString(1),key=r.getString(3),type=r.getString(6),state=r.getString(8);byte[] hash=r.getBytes(4);Long generation=r.getObject(5,Long.class),revision=r.getObject(9,Long.class);String day=r.getString(7);
            if(!"generation_intent".equals(r.getString(2))||key==null||key.isBlank()||key.getBytes(StandardCharsets.UTF_8).length>4096||hash==null||hash.length!=32||generation==null||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||day==null||revision==null||revision<0||revision>CanonicalJson.MAX_SAFE_INTEGER||!this.type.equals(type)||!ACTIVE.contains(state)||!Arrays.equals(hash,JdbcGenerationLineage.hash(key))||!JdbcFrozenInputs.stableId("intent",key+":"+generation).equals(id))throw invalid();return new JdbcSourceIntentScan.Candidate(id,key,hash,generation,type,LocalDate.parse(day),state,revision);
    }
    public List<JdbcSourceIntentScan.Origin> origins(String intent,String type){
        JdbcSourceDedupResults.requireReadOnly();if(intent==null||!intent.matches("[A-Za-z0-9._:-]{1,64}")||!this.type.equals(type))throw invalid();String nativeType="collection_handoff";
        var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",intent);
        return List.copyOf(jdbc.query("SELECT id,record_kind,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 2048 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 8192 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,acted_at,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload) FROM jobs WHERE target_type=? AND target_key_hash=? LIMIT 2",(r,n)->{var at=r.getTimestamp(11);return new JdbcSourceIntentScan.Origin(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBytes(5),r.getString(6),r.getString(7),r.getString(8),r.getBytes(9),r.getBytes(10),at==null?null:at.toInstant(),r.getObject(12,Long.class),r.getObject(13,Long.class));},nativeType,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target))));
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog source intent scan unavailable");}
}
