package com.lianxibu.site.repository.jobs;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded physical dependency index; caller verifies it against the original input. */
public final class JdbcFrozenDependencies {
    public record Part(String id,String owner,String stamp,long number,String dependency,String payload,byte[] hash,String upstreamId,String upstreamKind,String upstreamType) { }
    private final JdbcTemplate jdbc;
    public JdbcFrozenDependencies(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public List<Part> read(String owner){
        return jdbc.query("SELECT p.id,p.owner_job_id,p.stamp_id,p.part_no,p.dependency_id,p.payload_json,p.payload_hash,d.id,d.record_kind,d.task_type FROM jobs p LEFT JOIN jobs d ON d.id=p.dependency_id WHERE p.record_kind='snapshot_part' AND p.owner_job_id=? AND p.purpose='dependency' ORDER BY p.part_no,p.id LIMIT 10001",
            (rs,n)->new Part(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getBytes(7),rs.getString(8),rs.getString(9),rs.getString(10)),owner);
    }
}
