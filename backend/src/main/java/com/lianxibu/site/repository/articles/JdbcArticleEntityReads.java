package com.lianxibu.site.repository.articles;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
/** Read-only association evidence; no source body, dedup payload or identity key. */
public final class JdbcArticleEntityReads {
 public record Link(String id,String articleId,String sourceId,String category,String entityId,String state) { }
 public record Entity(String id,String category,Instant firstRecordedAt,LocalDate firstSeenBusinessDate,Instant lastSeenAt,Long occurrenceCount,Long repeatCount,String analysisState,long revision) { }
 private final JdbcTemplate jdbc;
 public JdbcArticleEntityReads(DataSource source){jdbc=new JdbcTemplate(source);}
 public List<Link> links(String articleId,String after){String sql="SELECT id,article_id,source_id,category,entity_id,state FROM source_run_items WHERE article_id=? AND terminal_state='accepted' AND effective_valid=1 AND (state IS NULL OR state<>'excluded')";Object[] args;if(after==null)args=new Object[]{articleId};else{sql+=" AND id>?";args=new Object[]{articleId,after};}return jdbc.query(sql+" ORDER BY id LIMIT 500",(rs,i)->new Link(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),args);}
 public Entity entity(String id){var rows=jdbc.query("SELECT id,category,first_recorded_at,first_seen_business_date,last_seen_at,occurrence_count,repeat_count,analysis_state,revision FROM analyzed_entities WHERE id=?",(rs,i)->new Entity(rs.getString(1),rs.getString(2),rs.getTimestamp(3)==null?null:rs.getTimestamp(3).toInstant(),rs.getDate(4)==null?null:rs.getDate(4).toLocalDate(),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant(),rs.getObject(6)==null?null:rs.getLong(6),rs.getObject(7)==null?null:rs.getLong(7),rs.getString(8),rs.getLong(9)),id);return rows.isEmpty()?null:rows.getFirst();}
}
