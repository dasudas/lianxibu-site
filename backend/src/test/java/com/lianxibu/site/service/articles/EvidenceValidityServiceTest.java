package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.articles.JdbcEvidenceValidity;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;

class EvidenceValidityServiceTest {
    JdbcTemplate jdbc;TransactionTemplate tx;EvidenceValidityService service;
    @BeforeEach void setup(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:evidence-"+System.nanoTime()+";DB_CLOSE_DELAY=-1");install(source,false);}
    void install(DataSource source,boolean mysql){
        jdbc=new JdbcTemplate(source);for(String name:new String[]{"source_run_items","article_revisions","articles"})jdbc.execute("DROP TABLE IF EXISTS "+name);
        jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY)");
        // H2 counts UTF-16 units; MySQL's actual VARCHAR(64) counts Unicode characters.
        jdbc.execute("CREATE TABLE article_revisions(id VARCHAR("+(mysql?64:128)+") PRIMARY KEY,article_id VARCHAR(64),validity_revision BIGINT NOT NULL DEFAULT 0,effective_valid BOOLEAN NOT NULL DEFAULT TRUE)");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),validity_revision BIGINT NOT NULL DEFAULT 0,effective_valid BOOLEAN NOT NULL DEFAULT TRUE)");
        jdbc.update("INSERT INTO articles VALUES('fictional-article')");jdbc.update("INSERT INTO article_revisions(id,article_id) VALUES('fictional-same','fictional-article')");
        jdbc.update("INSERT INTO source_run_items(id,category) VALUES('fictional-same','model')");
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));service=new EvidenceValidityService(new JdbcEvidenceValidity(source));
    }
    @Test void exactKindsKeepSeparateRevisionsAndStaleCasCannotWrite(){var article=key("article_revision","fictional-same");var catalog=key("catalog_evidence","fictional-same");tx.execute(s->{service.lock(article);service.update(article,0,1,false);return true;});assertEquals(new EvidenceValidityService.State(1,false),service.read(article).orElseThrow());assertEquals(new EvidenceValidityService.State(0,true),service.read(catalog).orElseThrow());assertThrows(RuntimeException.class,()->tx.execute(s->{service.update(article,0,1,true);return true;}));assertThrows(IllegalStateException.class,()->service.lock(article));}
    @Test void updateRollsBackAndAllCatalogCategoriesAreExplicit(){var article=key("article_revision","fictional-same");assertThrows(RuntimeException.class,()->tx.execute(s->{service.update(article,0,1,false);throw new IllegalStateException("fictional lost receipt");}));assertTrue(service.read(article).orElseThrow().valid());for(String category:new String[]{"agent","skill","workflow","model"}){jdbc.update("UPDATE source_run_items SET category=?",category);assertTrue(service.read(key("catalog_evidence","fictional-same")).isPresent());}jdbc.update("UPDATE source_run_items SET category='news'");assertThrows(IllegalStateException.class,()->service.read(key("catalog_evidence","fictional-same")));}
    @Test void unicodeIdentityUsesScalarBoundsAndRejectsUnknownOrFoldedIdentity(){String id="😀".repeat(64);jdbc.update("INSERT INTO article_revisions(id,article_id) VALUES(?,'fictional-article')",id);assertTrue(service.read(key("article_revision",id)).isPresent());assertThrows(IllegalArgumentException.class,()->service.read(key("article_revision",id+"😀")));assertTrue(service.read(key("article_revision","fictional-missing")).isEmpty());jdbc.update("UPDATE article_revisions SET article_id='fictional-missing' WHERE id='fictional-same'");assertThrows(IllegalStateException.class,()->tx.execute(s->{service.lock(key("article_revision","fictional-same"));return true;}));}
    static JsonNode key(String kind,String id){var node=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();node.put("kind",kind);node.put(kind.equals("article_revision")?"id":"item_id",id);return node;}
}
