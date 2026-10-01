package com.lianxibu.site.repository.content;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Hash is an index accelerator; the consumer rechecks full canonical target and proof. */
public final class JdbcVersionDispositionReads {
 public record Row(String id,String targetJson,byte[] targetHash,String payloadJson,byte[] payloadHash,String reason,Instant createdAt) { }
 private final JdbcTemplate jdbc;
 public JdbcVersionDispositionReads(DataSource source){jdbc=new JdbcTemplate(source);}
 public List<Row> scan(byte[] hash,Instant afterTime,String afterId,int limit){
  if(hash==null||hash.length!=32||limit<1||limit>500)throw new IllegalArgumentException("invalid disposition scan");
  String sql="SELECT id,target_key_json,target_key_hash,action_payload,action_payload_hash,reason,created_at FROM jobs WHERE record_kind='disposition' AND target_type='content_version' AND target_key_hash=?";
  var args=new ArrayList<Object>();args.add(hash);
  if(afterTime!=null){sql+=" AND (created_at>? OR (created_at=? AND id>?))";args.add(Timestamp.from(afterTime));args.add(Timestamp.from(afterTime));args.add(afterId);}
  sql+=" ORDER BY created_at,id LIMIT ?";args.add(limit);
  return jdbc.query(sql,(rs,index)->new Row(rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getString(4),rs.getBytes(5),rs.getString(6),rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant()),args.toArray());
 }
}
