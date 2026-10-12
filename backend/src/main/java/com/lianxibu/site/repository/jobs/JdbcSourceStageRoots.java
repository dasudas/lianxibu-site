package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Current native headers only. Original INPUT and result proofs must be checked by consumers. */
public final class JdbcSourceStageRoots {
    public static final class CapacityRejected extends IllegalStateException {public CapacityRejected(){super("original source stage native writer capacity unavailable");}}
    private static final Set<String> STATES=Set.of("waiting_input","prepared","queued","running","waiting_retry","cancel_requested","unknown_external","blocked","blocked_configuration","result_conflict","succeeded","failed","cancelled","discarded","superseded");
    private static final Set<String> APPLICATIONS=Set.of("APPLIED","CONTENT_REVISION_CONFLICT","SUPERSEDED","INVALID_EVIDENCE","DISCARDED");
    public record Native(String id,String intent,String key,long generation,long part,String type,LocalDate day,
            String state,long revision,int calls,int maximum,String inputState,String inputHash,Instant cutoff,
            String application,String resultHash,Instant appliedAt,Instant updatedAt){
        @Override public String toString(){return "SourceStageNativeRoot[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcSourceStageRoots(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Native find(String rootId){JdbcSourceDedupResults.requireReadOnly();return findRow(rootId,false);}
    /** Holds every captured native row until the caller's writer commits; never grants business completion. */
    public void lockOriginal(List<Native> expected){
        try {
            JdbcFrozenInputs.requireTx();if(expected==null||expected.isEmpty())throw invalid();if(expected.size()>100)throw new CapacityRejected();var ids=new HashSet<String>();
            for(var root:expected)if(root==null||root.id()==null||!root.id().matches("[0-9a-f]{64}")||!ids.add(root.id()))throw invalid();
            for(var root:expected.stream().sorted(Comparator.comparing(Native::id)).toList())if(!root.equals(findRow(root.id(),true)))throw invalid();
        }catch(CapacityRejected guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Native findRow(String rootId,boolean locked){
        try {
            if(rootId==null||!rootId.matches("[0-9a-f]{64}"))throw invalid();
            var rows=jdbc.query("SELECT id,record_kind,record_identity_hash,intent_id,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,logical_key_hash,generation,stage_part,task_type,business_date,state,revision,call_count,max_attempts,input_state,input_hash,input_cutoff_at,application_state,result_hash,applied_at,updated_at FROM jobs WHERE id=? LIMIT 2"+(locked?" FOR UPDATE":""),(r,n)->{
                String id=r.getString(1),intent=r.getString(4),key=r.getString(5),type=r.getString(9),state=r.getString(11),inputState=r.getString(15),application=r.getString(18);
                Long generation=r.getObject(7,Long.class),part=r.getObject(8,Long.class),revision=r.getObject(12,Long.class);
                Integer calls=r.getObject(13,Integer.class),maximum=r.getObject(14,Integer.class);
                if(!rootId.equals(id)||!"logical_task".equals(r.getString(2))||key==null||key.isBlank()||key.getBytes(StandardCharsets.UTF_8).length>4096
                        ||generation==null||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||part==null||part<0||part>=10000||revision==null||revision<0||revision>CanonicalJson.MAX_SAFE_INTEGER
                        ||calls==null||calls<0||calls>3||maximum==null||maximum!=3||type==null||!Set.of("news_dedup","news_value","agent_dedup","skill_dedup","workflow_dedup","model_dedup").contains(type)||state==null||!STATES.contains(state)
                        ||inputState!=null&&!Set.of("waiting_input","ready","no_input").contains(inputState)||application!=null&&!APPLICATIONS.contains(application)
                        ||!JdbcFrozenInputs.stableId("intent",key+":"+generation).equals(intent)||!Arrays.equals(r.getBytes(6),JdbcGenerationLineage.hash(key)))throw invalid();
                var natural=JsonMapper.shared().createObjectNode().put("record_kind","logical_task").put("natural_key",key+"\u0000"+generation+"\u0000"+part);
                if(!Arrays.equals(r.getBytes(3),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,natural))))throw invalid();
                String date=r.getString(10);Instant updated=time(r.getTimestamp(21));if(updated==null)throw invalid();
                return new Native(id,intent,key,generation,part,type,date==null?null:LocalDate.parse(date),state,revision,calls,maximum,inputState,hash(r.getBytes(16)),time(r.getTimestamp(17)),application,hash(r.getBytes(19)),time(r.getTimestamp(20)),updated);
            },rootId);
            if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static String hash(byte[] bytes){if(bytes==null)return null;if(bytes.length!=32)throw invalid();return HexFormat.of().formatHex(bytes);}
    private static Instant time(java.sql.Timestamp value){if(value==null)return null;Instant time=value.toInstant();if(time.getNano()%1000!=0)throw invalid();return time;}
    private static IllegalStateException invalid(){return new IllegalStateException("original source stage native header unavailable");}
}
