package com.lianxibu.site.repository.jobs;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Original intent identity, independent of its later processing state. */
public final class JdbcCollectionProofs {
    public record Intent(String id,String logicalKey,byte[] hash,long generation,String stage,LocalDate businessDate) { }
    private final JdbcTemplate jdbc;
    public JdbcCollectionProofs(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Intent read(String id){var rows=jdbc.query("SELECT id,logical_key,logical_key_hash,generation,task_type,business_date FROM jobs WHERE id=? AND record_kind='generation_intent'",(rs,n)->new Intent(rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getLong(4),rs.getString(5),rs.getDate(6)==null?null:rs.getDate(6).toLocalDate()),id);return rows.size()==1?rows.getFirst():null;}
}
