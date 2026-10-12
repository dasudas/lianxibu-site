package com.lianxibu.site.repository.articles;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Closed current-article projection; user predicates bind the complete record identity. */
public final class JdbcPrivateArticleReads {
    public record Row(JdbcArticleReads.ArticleRow article,String contentStatus) { }
    public record Page(List<Row> items,long total) { }
    private static final String COLUMNS="a.id,a.title,a.summary,a.source_id,s.name AS source_name,a.url,a.published_at,a.updated_at,a.revision,v.content_status";
    private static final String BASE=" FROM articles a JOIN sources s ON s.id=a.source_id LEFT JOIN article_revisions v ON v.id=a.latest_revision_id AND v.article_id=a.id";
    private static final RowMapper<Row> MAPPER=(rs,n)->new Row(new JdbcArticleReads.ArticleRow(rs.getString("id"),rs.getString("title"),rs.getString("summary"),rs.getString("source_id"),rs.getString("source_name"),rs.getString("url"),rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toInstant(),rs.getTimestamp("updated_at")==null?null:rs.getTimestamp("updated_at").toInstant(),rs.getLong("revision")),rs.getString("content_status"));
    private final JdbcTemplate jdbc;
    public JdbcPrivateArticleReads(DataSource source){jdbc=new JdbcTemplate(source);}
    public Row article(String id){var rows=jdbc.query("SELECT "+COLUMNS+BASE+" WHERE a.id=?",MAPPER,id);return rows.isEmpty()?null:rows.getFirst();}
    public Page page(String user,String source,String query,String status,long page,int size){
        var args=new ArrayList<Object>();
        var from=new StringBuilder(BASE);
        if(status!=null){from.append(" LEFT JOIN user_records r ON r.user_id=? AND r.package_id='' AND r.object_kind='article' AND r.object_id=a.id");args.add(user);}
        from.append(" WHERE 1=1");
        if(source!=null){from.append(" AND a.source_id=?");args.add(source);}
        if(query!=null){from.append(" AND a.title LIKE ? ESCAPE '!'");args.add("%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        if(status!=null){from.append(" AND COALESCE(NULLIF(r.status,''),'not_started')=?");args.add(status);}
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        args.add(size);args.add(Math.multiplyExact(page-1,size));
        return new Page(jdbc.query("SELECT "+COLUMNS+from+" ORDER BY a.published_at DESC,a.id LIMIT ? OFFSET ?",MAPPER,args.toArray()),total);
    }
}
