package com.lianxibu.site.repository.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Permanent ledger storage. Full target and payload proof is verified by the service. */
public final class JdbcVersionValidity {
    public record Event(String id,String targetType,String targetJson,byte[] targetHash,
                        String payloadJson,byte[] payloadHash,String actor,String reason,
                        Instant createdAt,Long revision,String reversesId) { }
    private static final String SELECT="SELECT id,target_type,target_key_json,target_key_hash,action_payload,action_payload_hash,operator_id,reason,created_at,validity_revision,reverses_disposition_id FROM jobs";
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    public JdbcVersionValidity(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);}
    public List<Event> scan(String type,byte[] hash,Instant after,String afterId,int limit){
        if(hash==null||hash.length!=32||limit<1||limit>500)throw new IllegalArgumentException("invalid ledger page");
        var args=new ArrayList<Object>();args.add(type);args.add(hash);String sql=SELECT+" WHERE record_kind='disposition' AND target_type=? AND target_key_hash=?";
        if(after!=null){sql+=" AND (created_at>? OR (created_at=? AND id>?))";args.add(Timestamp.from(after));args.add(Timestamp.from(after));args.add(afterId);}
        sql+=" ORDER BY created_at,id LIMIT ?";args.add(limit);return query(sql,args.toArray());
    }
    public Event action(String key){var rows=query(SELECT+" WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",JdbcResultState.sha(key));if(rows.size()>1)throw unavailable();return rows.isEmpty()?null:rows.getFirst();}
    public String append(JdbcJobRecords.Disposition value,long revision,String reverses,Instant now){
        JdbcFrozenInputs.requireTx();String id=records.appendDisposition(value,now);
        if(jdbc.update("UPDATE jobs SET validity_revision=?,reverses_disposition_id=? WHERE id=? AND record_kind='disposition' AND validity_revision IS NULL",revision,reverses,id)!=1)throw unavailable();
        return id;
    }
    private List<Event> query(String sql,Object... args){return jdbc.query(sql,(rs,n)->new Event(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBytes(4),rs.getString(5),rs.getBytes(6),rs.getString(7),rs.getString(8),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant(),rs.getObject(10)==null?null:rs.getLong(10),rs.getString(11)),args);}
    private static IllegalStateException unavailable(){return new IllegalStateException("permanent validity ledger unavailable");}
}
