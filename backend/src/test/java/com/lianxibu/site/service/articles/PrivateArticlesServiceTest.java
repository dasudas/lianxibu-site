package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.records.JdbcRecordReads;
import com.lianxibu.site.service.records.RecordReadService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class PrivateArticlesServiceTest {
    private static final Actor USER=new Actor("fixture-user","user"),OWNER=new Actor("fixture-owner","owner");
    private JdbcTemplate jdbc;private PrivateArticlesService service;
    @BeforeEach void setup(){
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:private-articles-"+System.nanoTime());jdbc=new JdbcTemplate(source);
        // Keep this isolated fixture database alive across the provider's independent connections.
        source.setURL(source.getURL()+";DB_CLOSE_DELAY=-1");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,name VARCHAR(200))");
        jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),title VARCHAR(512),summary CLOB,url VARCHAR(2000),published_at TIMESTAMP,updated_at TIMESTAMP,revision BIGINT,latest_revision_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,article_id VARCHAR(64),content_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE user_records(user_id VARCHAR(64),package_id VARCHAR(64),object_kind VARCHAR(16),object_id VARCHAR(64),revision BIGINT,status VARCHAR(32),self_assessment VARCHAR(32),note CLOB,artifact_url VARCHAR(2000),response_text CLOB,confirmed_body_revision BIGINT,updated_at TIMESTAMP,is_read BOOLEAN,is_favorite BOOLEAN,is_ignored BOOLEAN,PRIMARY KEY(user_id,object_kind,package_id,object_id))");
        jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),title VARCHAR(512),summary CLOB,first_recorded_at TIMESTAMP,first_seen_business_date DATE,last_seen_at TIMESTAMP,occurrence_count BIGINT,repeat_count BIGINT,analysis_state VARCHAR(32),revision BIGINT)");
        jdbc.update("INSERT INTO sources VALUES('fixture-source','Fictional source')");
        for(String id:new String[]{"a","b","c"})jdbc.update("INSERT INTO articles VALUES(?,'fixture-source',?,NULL,'https://example.invalid/article',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,NULL)",id,id+" 100% fiction");
        jdbc.update("INSERT INTO user_records VALUES('fixture-user','','article','a',1,'planned','unassessed','user private note',NULL,NULL,NULL,CURRENT_TIMESTAMP,FALSE,TRUE,FALSE)");
        jdbc.update("INSERT INTO user_records VALUES('fixture-owner','','article','b',1,'planned','unassessed','owner private note',NULL,NULL,NULL,CURRENT_TIMESTAMP,FALSE,TRUE,FALSE)");
        var tx=new DataSourceTransactionManager(source);
        service=new PrivateArticlesService(new JdbcPrivateArticleReads(source),new JdbcArticleReads(source),new RecordReadService(new JdbcRecordReads(source),tx),tx);
    }
    @Test void scopedStatusFilterMatchesRecordProjectionAndLiteralSearch(){
        var user=service.listArticles(USER,null,"100%","planned",1,20);
        assertEquals(1,user.total());assertEquals("a",user.items().getFirst().id());assertEquals("user private note",user.items().getFirst().record().note());
        assertNull(service.article(USER,"b").record());
        assertEquals("b",service.listArticles(OWNER,null,null,"planned",1,20).items().getFirst().id());
        assertEquals(2,service.listArticles(USER,null,null,"not_started",1,1).total());
        assertEquals(1,service.listArticles(USER,null,null,"not_started",2,1).items().size());
        assertEquals(0,service.listArticles(USER,null,"100_",null,1,20).total());
        jdbc.update("INSERT INTO articles VALUES('orphan','missing','orphan',NULL,'https://example.invalid',NULL,NULL,1,NULL)");
        assertEquals(3,service.listArticles(USER,null,null,null,1,20).total());
    }
    @Test void statusRequiresSelectedRevisionBelongingToArticleAndNoInferredLegacyFields(){
        jdbc.update("INSERT INTO article_revisions VALUES('revision-a','a','full')");
        jdbc.update("UPDATE articles SET latest_revision_id='revision-a' WHERE id IN ('a','b')");
        assertEquals("full",service.article(USER,"a").contentStatus());
        var legacy=service.article(USER,"b");assertEquals("unknown",legacy.contentStatus());assertNull(legacy.topic());assertNull(legacy.region());assertNull(legacy.summary());
    }
    @Test void entityReadsRequireOwnerAndMissingDataIsNotFound(){
        assertEquals(IdentityService.Refusal.FORBIDDEN,assertThrows(IdentityService.Rejected.class,()->service.listEntities(USER,null,null,1,20)).refusal());
        assertEquals(IdentityService.Refusal.NOT_FOUND,assertThrows(IdentityService.Rejected.class,()->service.article(USER,"missing")).refusal());
        assertEquals(IdentityService.Refusal.UNAUTHENTICATED,assertThrows(IdentityService.Rejected.class,()->service.article(null,"a")).refusal());
        jdbc.update("INSERT INTO analyzed_entities VALUES('entity','news_tech','Fictional',NULL,NULL,NULL,NULL,NULL,NULL,'legacy',0)");
        assertNull(service.entity(OWNER,"entity").occurrenceCount());assertNull(service.entity(OWNER,"entity").analysisState());
        jdbc.update("UPDATE analyzed_entities SET occurrence_count=3,repeat_count=1");
        assertThrows(IllegalStateException.class,()->service.entity(OWNER,"entity"));
    }
}
