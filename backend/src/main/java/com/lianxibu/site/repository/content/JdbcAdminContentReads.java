package com.lianxibu.site.repository.content;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
/** Internal current-row metadata scan; JSON is used only to identify a report period type. */
public final class JdbcAdminContentReads {
 public record Row(String kind,String id,String title,String state,long revision,Long bodyRevision,String reportJson) { }
 private final JdbcTemplate jdbc;
 public JdbcAdminContentReads(DataSource source){if(source==null)throw new IllegalArgumentException("content source required");jdbc=new JdbcTemplate(source);}
 public List<Row> scan(String kind,String state,String afterKind,String afterId,int size){
  if(size<1||size>500)throw new IllegalArgumentException("scan size");var args=new ArrayList<Object>();String filter="";
  if(kind!=null){filter+=" AND kind=?";args.add(kind);}if(state!=null){filter+=" AND state=?";args.add(state);}args.add(afterKind);args.add(afterKind);args.add(afterId);args.add(size);
  return jdbc.query("SELECT kind,id,title,state,revision,body_revision,CASE WHEN kind='report' THEN body_json ELSE NULL END AS report_json FROM contents WHERE 1=1"+filter+" AND (kind>? OR (kind=? AND id>?)) ORDER BY kind,id LIMIT ?",(rs,index)->{long body=rs.getLong("body_revision");Long version=rs.wasNull()?null:body;return new Row(rs.getString("kind"),rs.getString("id"),rs.getString("title"),rs.getString("state"),rs.getLong("revision"),version,rs.getString("report_json"));},args.toArray());
 }
}
