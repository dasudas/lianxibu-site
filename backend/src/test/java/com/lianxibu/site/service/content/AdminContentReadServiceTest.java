package com.lianxibu.site.service.content;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.content.JdbcAdminContentReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
class AdminContentReadServiceTest {
 private JdbcTemplate jdbc;private AdminContentReadService reads;private final Actor owner=new Actor("fictional-owner","owner");
 @BeforeEach void setup(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:fictional-content-catalog-"+System.nanoTime()+";DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE contents(kind VARCHAR(32),id VARCHAR(64),title VARCHAR(512),state VARCHAR(32),revision BIGINT,body_revision BIGINT,body_json CLOB,PRIMARY KEY(kind,id))");
 row("report","fictional-same","draft",null,"{\"type\":\"daily\",\"sections\":[]}");row("model","fictional-same","published",1L,"{\"type\":\"daily\"}");row("report","fictional-weekly","approved",2L,"{\"type\":\"weekly\"}");row("report","fictional-legacy","withdrawn",null,"{\"overview\":\"Fictional legacy\"}");row("report","fictional-duplicate","draft",null,"{\"type\":\"daily\",\"type\":\"weekly\"}");
 reads=new AdminContentReadService(new JdbcAdminContentReads(source),new DataSourceTransactionManager(source));}
 @Test void ownerGetsCurrentCompositeKeysAndServerFiltersBeforePagination(){var first=reads.list(owner,null,null,null,1,2);assertEquals(5,first.total());assertEquals("model",first.items().getFirst().kind());assertNull(first.items().getFirst().reportType());assertNull(first.items().get(1).bodyRevision());var daily=reads.list(owner,null,"draft","daily",1,20);assertEquals(1,daily.total());assertEquals("report",daily.items().getFirst().kind());assertNull(daily.items().getFirst().bodyRevision());assertEquals("daily",daily.items().getFirst().reportType());assertEquals(0,reads.list(owner,"report","published",null,1,20).total());assertTrue(reads.list(owner,null,null,null,9,20).items().isEmpty());}
 @Test void oldAndAmbiguousPeriodRemainUnknownAndBadActorsNeverRead(){var all=reads.list(owner,"report",null,null,1,20);assertEquals(4,all.total());assertEquals(2,all.items().stream().filter(x->x.reportType()==null).count());assertThrows(AdminContentReadService.Denied.class,()->reads.list(null,null,null,null,1,20));assertThrows(AdminContentReadService.Denied.class,()->reads.list(new Actor("fictional-user","user"),null,null,null,1,20));assertThrows(IllegalArgumentException.class,()->reads.list(owner,"model",null,"daily",1,20));assertThrows(IllegalArgumentException.class,()->reads.list(owner,null,"arbitrary",null,1,20));jdbc.execute("DROP TABLE contents");assertThrows(AdminContentReadService.Denied.class,()->reads.list(new Actor("fictional-user","user"),null,null,null,1,20));}
 private void row(String kind,String id,String state,Long bodyRevision,String json){jdbc.update("INSERT INTO contents VALUES(?,?,?, ?,3,?,?)",kind,id,"Fictional content "+id,state,bodyRevision,json);}
}
