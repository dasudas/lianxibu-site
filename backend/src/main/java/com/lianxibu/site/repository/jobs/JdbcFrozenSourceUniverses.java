package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Bounded source proof storage in the existing input purpose, with a separate stamp. */
public final class JdbcFrozenSourceUniverses {
    public record Part(String id,String owner,String purpose,String stamp,long number,String payload,byte[] hash) {
        public Part {hash=hash==null?null:hash.clone();}
        @Override public byte[] hash(){return hash==null?null:hash.clone();}
        @Override public String toString(){return "FrozenSourcePart[redacted]";}
    }
    private final JdbcTemplate jdbc;private final JdbcJobRecords records;
    public JdbcFrozenSourceUniverses(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);}
    public void lockOwner(String owner){owner(owner,true);}
    public void immutableOwner(String owner){owner(owner,false);}
    private void owner(String owner,boolean lock) {
        requireTransaction(lock);
        var rows=jdbc.query("SELECT id,record_kind FROM jobs WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->new String[]{rs.getString(1),rs.getString(2)},owner);
        if(rows.size()!=1||!owner.equals(rows.getFirst()[0])||!Set.of("generation_intent","logical_task").contains(rows.getFirst()[1]))throw unavailable();
    }
    public List<Part> read(String owner,String stamp){return read(owner,stamp,true);}
    public List<Part> immutableParts(String owner,String stamp){return read(owner,stamp,false);}
    private List<Part> read(String owner,String stamp,boolean lock) {
        requireTransaction(lock);long[] total={0};
        RowMapper<Part> mapper=(rs,n)->{
            long bytes=rs.getLong(7);if(rs.wasNull()||bytes<1||bytes>256*1024||(total[0]+=bytes)>96L*1024*1024)throw unavailable();
            return new Part(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getBytes(8));
        };
        return jdbc.query("SELECT id,owner_job_id,purpose,stamp_id,part_no,payload_json,OCTET_LENGTH(payload_json),payload_hash FROM jobs"
                +" WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='input' AND stamp_id=? ORDER BY part_no,id LIMIT 514"+(lock?" FOR UPDATE":""),mapper,owner,stamp);
    }
    public void put(String owner,String stamp,int number,String payload,Instant now) {
        JdbcFrozenInputs.requireTx();
        var body=CanonicalJson.parse(payload);
        if(CanonicalJson.bytes(body).length>256*1024)throw unavailable();
        records.putPart(new JdbcJobRecords.Part(partId(owner,stamp,number),owner,"input",stamp,number,
                JdbcFrozenInputs.canonical(body),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body)),now);
    }
    public static String partId(String owner,String stamp,long number){return JdbcFrozenInputs.stableId("source-universe-part",owner+":"+stamp+":"+number);}
    static void requireTransaction(boolean writer){
        if(writer)JdbcFrozenInputs.requireTx();
        else if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("frozen source universe storage unavailable");}
}
