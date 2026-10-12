package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Immutable, bounded partial P2 blocks. Staging alone never establishes completion. */
public final class JdbcSourceValuePartialBlocks {
    public record Part(String id,String owner,String stamp,long number,String payload){@Override public String toString(){return "SourceValuePartialBlock[redacted]";}}
    private static final String COLUMNS="id,record_kind,state,owner_job_id,purpose,stamp_id,part_no,CASE WHEN OCTET_LENGTH(payload_json) BETWEEN 1 AND 262144 THEN payload_json ELSE NULL END,payload_hash,OCTET_LENGTH(payload_json)";
    private final JdbcTemplate jdbc;private final JdbcJobRecords records;private final JdbcFrozenSourcePlans owners;
    public JdbcSourceValuePartialBlocks(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);owners=new JdbcFrozenSourcePlans(source);}
    public void lockOwner(String owner){validate(owners.lockOwner(owner));}
    public void immutableOwner(String owner){validate(owners.immutableOwner(owner));}
    private static void validate(JdbcFrozenSourcePlans.Owner owner){
        if(!"generation_intent".equals(owner.kind())||!"news_value".equals(owner.type())||owner.key()==null||owner.key().isBlank()||owner.generation()<1||owner.generation()>CanonicalJson.MAX_SAFE_INTEGER||!Arrays.equals(owner.keyHash(),JdbcGenerationLineage.hash(owner.key()))||!owner.id().equals(JdbcFrozenInputs.stableId("intent",owner.key()+":"+owner.generation())))throw invalid();
    }
    public Part part(String owner,String stamp,int number){
        JdbcFrozenSourceUniverses.requireTransaction(false);if(number<0||number>128)throw invalid();
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM jobs WHERE id=? OR (record_kind='snapshot_part' AND owner_job_id=? AND purpose='result' AND stamp_id=? AND part_no=?) LIMIT 3",(r,n)->read(r,owner,stamp),partId(owner,stamp,number),owner,stamp,number);
        if(rows.size()>1)throw invalid();if(rows.isEmpty())return null;var row=rows.getFirst();if(row.number()!=number)throw invalid();return row;
    }
    public List<Part> parts(String owner,String stamp){
        JdbcFrozenSourceUniverses.requireTransaction(false);long[] total={0};
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM jobs WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='result' AND stamp_id=? ORDER BY part_no,id LIMIT 130",(r,n)->{var row=read(r,owner,stamp);total[0]+=r.getLong(10);if(total[0]>24L*1024*1024)throw invalid();return row;},owner,stamp);
        if(rows.size()>129)throw invalid();for(int n=0;n<rows.size();n++)if(rows.get(n).number()!=n)throw invalid();return List.copyOf(rows);
    }
    private static Part read(java.sql.ResultSet r,String owner,String stamp)throws java.sql.SQLException{
        long size=r.getLong(10);if(r.wasNull()||size<1||size>262144)throw invalid();long number=r.getLong(7);if(r.wasNull()||number<0||number>128)throw invalid();String raw=r.getString(8);var payload=CanonicalJson.parse(raw);String canonical=JdbcFrozenInputs.canonical(payload);
        if(!partId(owner,stamp,number).equals(r.getString(1))||!"snapshot_part".equals(r.getString(2))||!"frozen".equals(r.getString(3))||!owner.equals(r.getString(4))||!"result".equals(r.getString(5))||!stamp.equals(r.getString(6))||!Arrays.equals(r.getBytes(9),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,payload))))throw invalid();return new Part(r.getString(1),owner,stamp,number,canonical);
    }
    public void put(String owner,String stamp,int number,String payload,Instant now){
        JdbcFrozenInputs.requireTx();if(number<0||number>128||payload==null)throw invalid();var body=CanonicalJson.parse(payload);byte[] bytes=CanonicalJson.bytes(body);if(bytes.length>262144)throw invalid();records.putPart(new JdbcJobRecords.Part(partId(owner,stamp,number),owner,"result",stamp,number,JdbcFrozenInputs.canonical(body),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body)),now);
    }
    public static String stamp(String owner,String indexHash){WireValues.id(owner,false);if(indexHash==null||!indexHash.matches("[0-9a-f]{64}"))throw invalid();return JdbcFrozenInputs.stableId("source-value-partial",owner+":"+indexHash);}
    public static String partId(String owner,String stamp,long number){return JdbcFrozenInputs.stableId("source-value-partial-part",owner+":"+stamp+":"+number);}
    private static IllegalStateException invalid(){return new IllegalStateException("original partial source value block unavailable");}
}
