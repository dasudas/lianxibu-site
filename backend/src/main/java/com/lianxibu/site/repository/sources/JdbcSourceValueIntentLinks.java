package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Bounded original source headers and reference append. The caller validates native intent proof. */
public final class JdbcSourceValueIntentLinks {
    public record Header(String runId,String sourceId,String sourceIdentity,String category,String kind,LocalDate day,long generation,String manifestHash,long expected,long accepted,long excluded,long pending,String activation,String delivery,long revision,List<String> refs){
        public Header {refs=List.copyOf(refs);}
        @Override public String toString(){return "SourceValueIntentSourceHeader[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcSourceValueIntentLinks(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Header read(String runId){return read(runId,false);}
    public Header locked(String runId){return read(runId,true);}
    private Header read(String runId,boolean lock){
        requireTransaction(lock);id(runId);
        var rows=jdbc.query("SELECT r.id,r.source_id,s.id,s.category,r.run_kind,r.business_date,r.generation,r.manifest_hash,r.expected_count,r.accepted_count,r.excluded_count,r.pending_count,r.activation_stage,r.delivery_state,r.revision,CASE WHEN OCTET_LENGTH(r.job_refs_json) BETWEEN 1 AND 65536 THEN r.job_refs_json ELSE NULL END,OCTET_LENGTH(r.job_refs_json) FROM source_runs r JOIN sources s ON s.id=r.source_id WHERE r.id=?"+(lock?" FOR UPDATE":""),(r,n)->{
            var day=r.getDate(6);var hash=r.getBytes(8);Long generation=r.getObject(7,Long.class),expected=r.getObject(9,Long.class),accepted=r.getObject(10,Long.class),excluded=r.getObject(11,Long.class),pending=r.getObject(12,Long.class),revision=r.getObject(15,Long.class),bytes=r.getObject(17,Long.class);
            if(day==null||hash==null||hash.length!=32||generation==null||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||revision==null||revision<0||revision>CanonicalJson.MAX_SAFE_INTEGER||expected==null||accepted==null||excluded==null||pending==null||bytes==null||bytes<1||bytes>65536)throw invalid();
            for(long count:new long[]{expected,accepted,excluded,pending})if(count<0||count>10000)throw invalid();if(expected!=accepted+excluded+pending)throw invalid();
            String source=r.getString(2),identity=r.getString(3);if(source==null||!source.equals(identity))throw invalid();
            return new Header(r.getString(1),source,identity,r.getString(4),r.getString(5),day.toLocalDate(),generation,HexFormat.of().formatHex(hash),expected,accepted,excluded,pending,r.getString(13),r.getString(14),revision,references(r.getString(16)));
        },runId);if(rows.size()!=1||!runId.equals(rows.getFirst().runId()))throw invalid();return rows.getFirst();
    }
    public long link(String runId,String p1Intent,String p2Intent){
        requireTransaction(true);id(p1Intent);id(p2Intent);if(p1Intent.equals(p2Intent))throw invalid();var original=locked(runId);
        if(!original.refs().contains(p1Intent))throw invalid();if(original.refs().contains(p2Intent))return original.revision();if(original.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw invalid();
        var array=JsonMapper.shared().createArrayNode();original.refs().forEach(array::add);array.add(p2Intent);byte[] raw=CanonicalJson.bytes(array);if(raw.length>65536)throw invalid();
        if(jdbc.update("UPDATE source_runs SET job_refs_json=?,revision=revision+1 WHERE id=? AND revision=?",new String(raw,StandardCharsets.UTF_8),runId,original.revision())!=1)throw invalid();return original.revision()+1;
    }
    private static List<String> references(String raw){
        if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>65536)throw invalid();var n=CanonicalJson.parse(raw);if(!n.isArray()||n.size()>10000)throw invalid();var result=new ArrayList<String>();var seen=new HashSet<String>();
        for(var value:n){if(!value.isTextual())throw invalid();String reference=value.textValue();id(reference);if(!seen.add(reference))throw invalid();result.add(reference);}return List.copyOf(result);
    }
    private static void id(String n){if(n==null||!n.matches("[A-Za-z0-9._:-]{1,64}"))throw invalid();}
    private static void requireTransaction(boolean writer){if(!TransactionSynchronizationManager.isActualTransactionActive()||writer&&TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("source value intent reference storage unavailable");}
}
