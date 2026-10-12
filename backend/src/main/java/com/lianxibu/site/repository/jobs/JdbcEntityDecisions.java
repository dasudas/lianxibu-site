package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Append-only entity decision receipts in the existing jobs disposition ledger. */
public final class JdbcEntityDecisions {
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    public JdbcEntityDecisions(DataSource source) { jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source); }
    public JdbcJobRecords.Disposition read(String id,String actionKey) {
        var rows=jdbc.query("SELECT id,target_type,target_key_json,target_key_hash,operator_id,reason,action_payload,action_payload_hash,action_key_hash FROM jobs WHERE id=? AND record_kind='disposition'",(rs,n)->{
            String target=rs.getString(3),payload=rs.getString(7);
            if(!same(rs.getBytes(4),hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(target))))
                ||!same(rs.getBytes(8),hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(payload))))
                ||!same(rs.getBytes(9),hash(actionKey)))throw new IllegalStateException("entity decision proof unavailable");
            return new JdbcJobRecords.Disposition(rs.getString(1),actionKey,rs.getString(2),target,rs.getString(5),rs.getString(6),payload);
        },id);
        if(rows.size()>1)throw new IllegalStateException("duplicate entity decision");return rows.isEmpty()?null:rows.getFirst();
    }
    public void append(JdbcJobRecords.Disposition value,Instant now) { records.appendDisposition(value,now); }
    /** UTC native timestamps for explicit v2 count facts; v1 byte replay remains compatible. */
    public void appendUtc(JdbcJobRecords.Disposition value,Instant now){records.appendDispositionUtc(value,now);}
    private static boolean same(byte[] a,byte[] b) {return a!=null&&MessageDigest.isEqual(a,b);}
    private static byte[] hash(String value) {
        try {return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
