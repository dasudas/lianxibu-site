package com.lianxibu.site.repository.jobs;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only physical identity cross-check, separate from immutable original plan proofs. */
public final class JdbcSourceStageBirth {
    public record Root(String id,String key,byte[] keyHash,Long generation,Long part,String type,String intent,Integer maximum,Integer calls){
        public Root{keyHash=keyHash==null?null:keyHash.clone();}
        @Override public byte[] keyHash(){return keyHash==null?null:keyHash.clone();}
        @Override public String toString(){return "SourceStageBirthRoot[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcSourceStageBirth(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Root root(String id){
        JdbcFrozenSourceUniverses.requireTransaction(false);
        var rows=jdbc.query("SELECT id,CASE WHEN OCTET_LENGTH(logical_key)<=1024 THEN logical_key ELSE NULL END,logical_key_hash,generation,stage_part,task_type,intent_id,max_attempts,call_count,OCTET_LENGTH(logical_key)"
                +" FROM jobs WHERE id=? AND record_kind='logical_task' LIMIT 2",(rs,n)->{
                    long bytes=rs.getLong(10);if(rs.wasNull()||bytes<1||bytes>1024)throw unavailable();
                    return new Root(rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getObject(4,Long.class),rs.getObject(5,Long.class),rs.getString(6),rs.getString(7),rs.getObject(8,Integer.class),rs.getObject(9,Integer.class));
                },id);
        if(rows.size()>1)throw unavailable();return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean previousRootExists(byte[] keyHash,long generation,long part){
        JdbcFrozenSourceUniverses.requireTransaction(false);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM jobs WHERE record_kind='logical_task' AND logical_key_hash=? AND generation=? AND stage_part=?)",Boolean.class,keyHash,generation,part));
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("source stage birth storage unavailable");}
}
