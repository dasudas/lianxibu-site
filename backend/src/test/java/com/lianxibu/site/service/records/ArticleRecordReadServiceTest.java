package com.lianxibu.site.service.records;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.records.JdbcArticleRecordReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ArticleRecordReadServiceTest {
    private JdbcTemplate jdbc;
    private ArticleRecordReadService reads;
    private final Actor actor = new Actor("fictional-user", "user");
    @BeforeEach void setup() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:fictional-article-records-"+System.nanoTime()+";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sources(id VARCHAR(64) PRIMARY KEY,name VARCHAR(512))");
        jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,title VARCHAR(512),source_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE user_records(user_id VARCHAR(64),package_id VARCHAR(64),object_kind VARCHAR(32),object_id VARCHAR(64),revision BIGINT,status VARCHAR(32),self_assessment VARCHAR(32),note CLOB,artifact_url VARCHAR(2000),response_text CLOB,confirmed_body_revision BIGINT,updated_at TIMESTAMP,is_read BOOLEAN,is_favorite BOOLEAN,is_ignored BOOLEAN)");
        jdbc.update("INSERT INTO sources VALUES('fictional-source','Fictional source')");
        jdbc.update("INSERT INTO articles VALUES('fictional-one','Fictional 100%_title','fictional-source'),('fictional-two','Fictional second','fictional-source')");
        row("fictional-user","","article","fictional-one","planned",true);
        row("fictional-user","","article","fictional-two","completed",false);
        row("fictional-user","","article","fictional-missing","",false);
        row("fictional-other","","article","fictional-one","learning",true);
        row("fictional-user","fictional-package","question","fictional-one","learning",true);
        reads = new ArticleRecordReadService(new JdbcArticleRecordReads(source),new DataSourceTransactionManager(source));
    }
    @Test void serverFiltersBeforePaginationWhileStatsCoverAllOwnedArticleRecords() {
        var filtered = reads.list(actor,"100%_", "planned",true,1,1);
        assertEquals(1,filtered.total()); assertEquals(3,filtered.stats().total());
        assertEquals(1,filtered.stats().favorite()); assertEquals(1,filtered.stats().planned());
        assertEquals(0,filtered.stats().learning()); assertEquals(1,filtered.stats().completed());
        assertEquals("fictional-one",filtered.items().getFirst().record().objectId());
        assertEquals("Fictional source",filtered.items().getFirst().article().sourceName());
        assertEquals("Fictional own note",filtered.items().getFirst().record().note());
        assertEquals(0,reads.list(actor,"100x_",null,null,1,20).total());
        assertEquals(0,reads.list(actor,null,null,null,9,20).items().size());
    }
    @Test void missingArticleIsNullAndLegacyStateAndOwnershipStayExplicit() {
        var result=reads.list(actor,null,"not_started",false,1,20);
        assertEquals(1,result.total()); assertNull(result.items().getFirst().article());
        assertEquals("not_started",result.items().getFirst().record().status());
        assertEquals(0,reads.list(actor,"Fictional", "not_started",null,1,20).total());
        assertThrows(RuntimeException.class,()->reads.list(null,null,null,null,1,20));
        assertThrows(RuntimeException.class,()->reads.list(new Actor("fictional-user","collector"),null,null,null,1,20));
        assertThrows(IllegalArgumentException.class,()->reads.list(actor,null,"unknown",null,1,20));
        assertThrows(IllegalArgumentException.class,()->reads.list(actor,"x".repeat(257),null,null,1,20));
    }
    private void row(String user,String pack,String kind,String id,String status,boolean favorite) {
        jdbc.update("INSERT INTO user_records VALUES(?,?,?,?,1,?,'unassessed','Fictional own note',NULL,NULL,NULL,TIMESTAMP '2026-01-01 00:00:00',FALSE,?,FALSE)",user,pack,kind,id,status,favorite);
    }
}
