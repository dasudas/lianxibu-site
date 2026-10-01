package com.lianxibu.site.repository.records;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Current article records and closed article metadata, always scoped by user. */
public final class JdbcArticleRecordReads {
    public record Info(String id,String title,String sourceName) { }
    public record Item(Info article,JdbcRecordReads.Row record) { }
    public record Stats(long total,long favorite,long planned,long learning,long completed) { }
    public record Page(List<Item> items,long total,Stats stats) { }
    private final JdbcTemplate jdbc;
    private static final String BASE=" FROM user_records r LEFT JOIN articles a ON a.id=r.object_id LEFT JOIN sources s ON s.id=a.source_id WHERE r.user_id=? AND r.object_kind='article' AND r.package_id=''";
    public JdbcArticleRecordReads(DataSource source) { if(source==null)throw new IllegalArgumentException("data source required");jdbc=new JdbcTemplate(source); }
    public Page page(String user,String q,String status,Boolean favorite,long page,int size) {
        StringBuilder filtered=new StringBuilder(BASE);var args=new ArrayList<Object>();args.add(user);
        if(q!=null){filtered.append(" AND a.title LIKE ? ESCAPE '!'");args.add("%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        if(status!=null){filtered.append(" AND COALESCE(NULLIF(r.status,''),'not_started')=?");args.add(status);}
        if(favorite!=null){filtered.append(" AND r.is_favorite=?");args.add(favorite);}
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+filtered,Long.class,args.toArray());
        Stats stats=jdbc.queryForObject("SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN is_favorite THEN 1 ELSE 0 END),0) AS favorite,COALESCE(SUM(CASE WHEN status='planned' THEN 1 ELSE 0 END),0) AS planned,COALESCE(SUM(CASE WHEN status='learning' THEN 1 ELSE 0 END),0) AS learning,COALESCE(SUM(CASE WHEN status='completed' THEN 1 ELSE 0 END),0) AS completed FROM user_records WHERE user_id=? AND object_kind='article' AND package_id=''",(rs,n)->new Stats(rs.getLong("total"),rs.getLong("favorite"),rs.getLong("planned"),rs.getLong("learning"),rs.getLong("completed")),user);
        args.add(size);args.add((page-1)*size);
        List<Item> rows=jdbc.query("SELECT r.user_id,r.package_id,r.object_kind,r.object_id,r.revision,r.status,r.self_assessment,r.note,r.artifact_url,r.confirmed_body_revision,r.updated_at,r.is_read,r.is_favorite,r.is_ignored,a.id AS article_id,a.title,s.name AS source_name"+filtered+" ORDER BY r.updated_at DESC,r.object_id LIMIT ? OFFSET ?",(rs,n)->{
            String assessment=rs.getString("self_assessment");
            var record=new JdbcRecordReads.Row(rs.getString("user_id"),rs.getString("package_id"),rs.getString("object_kind"),rs.getString("object_id"),rs.getLong("revision"),rs.getString("status"),assessment==null||assessment.isEmpty()?"unassessed":assessment,rs.getString("note"),rs.getString("artifact_url"),null,rs.getObject("confirmed_body_revision",Long.class),rs.getTimestamp("updated_at").toInstant(),rs.getBoolean("is_read"),rs.getBoolean("is_favorite"),rs.getBoolean("is_ignored"));
            String id=rs.getString("article_id");return new Item(id==null?null:new Info(id,rs.getString("title"),rs.getString("source_name")),record);
        },args.toArray());
        return new Page(List.copyOf(rows),total==null?0:total,stats);
    }
}
