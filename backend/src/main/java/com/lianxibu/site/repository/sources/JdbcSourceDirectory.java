package com.lianxibu.site.repository.sources;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
public final class JdbcSourceDirectory {
    public record Page(List<String> ids,long total) { }
    private final JdbcTemplate jdbc;
    public JdbcSourceDirectory(DataSource source){jdbc=new JdbcTemplate(source);}
    public Page page(long page,int size){return new Page(jdbc.query("SELECT id FROM sources ORDER BY id LIMIT ? OFFSET ?",(rs,n)->rs.getString(1),size,Math.multiplyExact(page-1,size)),jdbc.queryForObject("SELECT COUNT(*) FROM sources",Long.class));}
}
