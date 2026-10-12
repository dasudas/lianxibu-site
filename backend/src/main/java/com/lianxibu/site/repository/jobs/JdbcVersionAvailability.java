package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Exact immutable version observed after its original transaction committed. */
public final class JdbcVersionAvailability {
    private static final String TYPE="content_availability",ACTOR="version-observer";
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    public JdbcVersionAvailability(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);}
    public Instant read(ContentVersionKey target,String inputHash){
        String version=JdbcFrozenContentVersions.version(target),action=key(target),id=JdbcFrozenInputs.stableId("availability",action);
        if(inputHash==null||!inputHash.matches("[0-9a-f]{64}"))throw invalid();
        byte[] identity=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,
            JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",action)));
        var rows=jdbc.query("SELECT id,record_kind,state,revision,call_count,max_attempts,priority_no,record_identity_hash,"+
            "action_key_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 4096 THEN target_key_json ELSE NULL END,"+
            "target_key_hash,operator_id,reason,acted_at,created_at,updated_at,eligible_at,"+
            "CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 4096 THEN action_payload ELSE NULL END,action_payload_hash,"+
            "business_key,task_kind,target_id,root_job_id,intent_id,generation,task_type,logical_key,attempt_no,owner_job_id,"+
            "purpose,stamp_id,part_no FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2",(r,n)->{
                if(r.getString(11)==null||r.getString(19)==null)throw invalid();
                String actualTarget=JdbcFrozenInputs.canonical(CanonicalJson.parse(r.getString(11)));
                var payload=CanonicalJson.parse(r.getString(19));String raw=JdbcFrozenInputs.canonical(payload);
                Set<String> fields=Set.of("schema_version","input_hash","observed_at");WireValues.object(payload,fields,fields);
                if(!"committed-version-observation-v1".equals(WireValues.text(payload.get("schema_version")))
                    ||!inputHash.equals(WireValues.sha256(payload.get("input_hash"))))throw invalid();
                Instant observed=Instant.parse(WireValues.text(payload.get("observed_at")));
                if(observed.getNano()%1000!=0||!observed.toString().equals(WireValues.text(payload.get("observed_at"))))throw invalid();
                if(!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"applied".equals(r.getString(3))
                    ||r.getObject(4)==null||r.getLong(4)!=0||r.getObject(5)==null||r.getLong(5)!=0||r.getObject(6)!=null
                    ||r.getObject(7)==null||r.getLong(7)!=0||!Arrays.equals(identity,r.getBytes(8))
                    ||!Arrays.equals(sha(action),r.getBytes(9))||!TYPE.equals(r.getString(10))
                    ||!version.equals(actualTarget)||!Arrays.equals(sha(actualTarget),r.getBytes(12))
                    ||!ACTOR.equals(r.getString(13))||!"Observed committed immutable content version".equals(r.getString(14))
                    ||!Arrays.equals(sha(raw),r.getBytes(20))||!("disposition:"+HexFormat.of().formatHex(identity)).equals(r.getString(21))
                    ||!"disposition".equals(r.getString(22))||!id.equals(r.getString(23)))throw invalid();
                for(int field=15;field<=18;field++){
                    var stamp=r.getTimestamp(field,Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                    if(stamp==null||stamp.getNanos()%1000!=0||!observed.equals(stamp.toInstant()))throw invalid();
                }
                for(int field=24;field<=33;field++)if(r.getObject(field)!=null)throw invalid();
                return observed;
            },id,sha(action),identity);
        if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();return rows.getFirst();
    }
    public void save(ContentVersionKey target,String inputHash,Instant observed){
        JdbcFrozenInputs.requireTx();if(observed==null||observed.getNano()%1000!=0||inputHash==null||!inputHash.matches("[0-9a-f]{64}"))throw invalid();
        Instant old=read(target,inputHash);if(old!=null){if(!old.equals(observed))throw invalid();return;}
        var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version","committed-version-observation-v1");
        payload.put("input_hash",inputHash);payload.put("observed_at",observed.toString());
        records.appendDispositionUtc(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("availability",key(target)),key(target),TYPE,
            JdbcFrozenContentVersions.version(target),ACTOR,"Observed committed immutable content version",JdbcFrozenInputs.canonical(payload)),observed);
    }
    public static String key(ContentVersionKey target){return "version-observed:"+JdbcResultState.sha(JdbcFrozenContentVersions.version(target));}
    private static byte[] sha(String text){return HexFormat.of().parseHex(JdbcResultState.sha(text));}
    private static IllegalStateException invalid(){return new IllegalStateException("committed version observation unavailable");}
}
