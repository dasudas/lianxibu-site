package com.lianxibu.site.repository.articles;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current native flags and bounded entity identity; never an entity history reconstruction. */
public final class JdbcNewsValueEntities {
    public record MemberFlags(String id,Integer valid,Long revision) { }
    public record Entity(String id,String category,String identityKey,byte[] identityHash,LocalDate firstSeenBusinessDate,
                         Integer legacy,Long revision,Instant firstRecordedAt) {
        public Entity {identityHash=identityHash==null?null:identityHash.clone();}
        @Override public byte[] identityHash(){return identityHash==null?null:identityHash.clone();}
        @Override public String toString(){return "ValueEntityRow[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcNewsValueEntities(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public MemberFlags memberFlags(String id) {
        requireTx();var rows=jdbc.query("SELECT id,effective_valid,revision FROM source_run_items WHERE id=? LIMIT 2 FOR UPDATE",
                (r,n)->new MemberFlags(r.getString(1),r.getObject(2)==null?null:r.getInt(2),r.getObject(3)==null?null:r.getLong(3)),id);
        if(rows.size()>1)throw unavailable();return rows.isEmpty()?null:rows.getFirst();
    }
    public Entity entity(String id) {
        requireTx();var rows=jdbc.query("SELECT id,category,OCTET_LENGTH(identity_key),CASE WHEN OCTET_LENGTH(identity_key)<=4096 THEN identity_key ELSE NULL END,"
                +"identity_hash,first_seen_business_date,legacy_flag,revision,first_recorded_at FROM analyzed_entities WHERE id=? LIMIT 2 FOR UPDATE",(r,n)->{
                    long bytes=r.getLong(3);if(r.wasNull()||bytes<1||bytes>4096)throw unavailable();
                    return new Entity(r.getString(1),r.getString(2),r.getString(4),r.getBytes(5),r.getDate(6)==null?null:r.getDate(6).toLocalDate(),r.getObject(7)==null?null:r.getInt(7),
                            r.getObject(8)==null?null:r.getLong(8),r.getTimestamp(9)==null?null:r.getTimestamp(9).toInstant());
                },id);
        if(rows.size()>1)throw unavailable();return rows.isEmpty()?null:rows.getFirst();
    }
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw unavailable();}
    private static IllegalStateException unavailable(){return new IllegalStateException("current bounded value entity unavailable");}

    public Map<String,MemberFlags> memberFlagsBatch(Set<String> ids){
        requireTx();var ordered=exactBatch(ids,100);var rows=jdbc.query("SELECT id,effective_valid,revision FROM source_run_items WHERE id IN ("+marks(ordered.size())+") ORDER BY id FOR UPDATE",(r,n)->new MemberFlags(r.getString(1),r.getObject(2)==null?null:r.getInt(2),r.getObject(3)==null?null:r.getLong(3)),ordered.toArray());var result=new LinkedHashMap<String,MemberFlags>();
        for(var row:rows)if(!ids.contains(row.id())||result.putIfAbsent(row.id(),row)!=null)throw unavailable();return Collections.unmodifiableMap(result);
    }
    public Map<String,Entity> entitiesBatch(Set<String> ids){
        requireTx();var ordered=exactBatch(ids,100);var rows=jdbc.query("SELECT id,category,OCTET_LENGTH(identity_key),CASE WHEN OCTET_LENGTH(identity_key)<=4096 THEN identity_key ELSE NULL END,"
                +"identity_hash,first_seen_business_date,legacy_flag,revision,first_recorded_at FROM analyzed_entities WHERE id IN ("+marks(ordered.size())+") ORDER BY id FOR UPDATE",(r,n)->{
                    long bytes=r.getLong(3);if(r.wasNull()||bytes<1||bytes>4096)throw unavailable();
                    return new Entity(r.getString(1),r.getString(2),r.getString(4),r.getBytes(5),r.getDate(6)==null?null:r.getDate(6).toLocalDate(),r.getObject(7)==null?null:r.getInt(7),
                            r.getObject(8)==null?null:r.getLong(8),r.getTimestamp(9)==null?null:r.getTimestamp(9).toInstant());
                },ordered.toArray());var result=new LinkedHashMap<String,Entity>();
        for(var row:rows)if(!ids.contains(row.id())||result.putIfAbsent(row.id(),row)!=null)throw unavailable();return Collections.unmodifiableMap(result);
    }

    private static List<String> exactBatch(Set<String> ids,int maximum){
        if(ids==null||ids.isEmpty()||ids.size()>maximum)throw new IllegalArgumentException("invalid exact native batch");
        var ordered=ids.stream().sorted().toList();for(var id:ordered)com.lianxibu.site.domain.protocol.WireValues.id(id,false);return ordered;
    }
    private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}

}
