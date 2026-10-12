package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.articles.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CatalogCandidateHistoryTest {
    DataSource source;JdbcTemplate jdbc;TransactionTemplate tx;CatalogCandidateHistoryService service;
    final Clock clock=Clock.fixed(CatalogCandidateRecallTest.CUT,ZoneOffset.UTC);
    DataSource source(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:fictional-catalog-candidate-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL;TIME ZONE=UTC");return ds;}
    @BeforeEach void setup(){source=source();jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),identity_key TEXT,identity_hash BINARY(32),title VARCHAR(512),summary TEXT,revision BIGINT,first_recorded_at TIMESTAMP(6),analysis_state VARCHAR(32),legacy_flag INT)");
        for(String category:List.of("agent","skill","workflow","model"))insert("fictional-"+category,category,0);
        insert("fictional-legacy-agent","agent",1);service=service("agent");}
    CatalogCandidateHistoryService service(String category){return new CatalogCandidateHistoryService(source,category,clock);}
    void insert(String id,String category,int legacy){String key=CatalogCandidateRecallTest.identity(category,id);
        jdbc.update("INSERT INTO analyzed_entities VALUES(?,?,?,?,?,NULL,3,?,'pending',?)",id,category,key,JdbcEntityDedup.identityHash(category,key),"Fictional retained title",Timestamp.from(clock.instant().minusSeconds(1)),legacy);}
    NewsCandidateRecall.CategoryHistory read(){return tx.execute(a->service.capture());}
    @Test void fourCategoriesCaptureOnlyTheirExactCurrentFactsAndOneNativeMicrosecondPoint(){
        for(String category:List.of("agent","skill","workflow","model")){
            service=service(category);var result=read();assertEquals(category,result.category());assertEquals(clock.instant(),result.facts().cutoff());
            assertEquals(category.equals("agent")?2:1,result.facts().entities().size());
            for(var entity:result.facts().entities()){assertTrue(entity.id().contains(category));assertEquals(clock.instant(),entity.metadataAsOf());assertEquals(3,entity.revision());assertEquals(clock.instant().minusSeconds(1),entity.firstRecordedAt());}
            service=service(category);assertEquals(result,read());
        }
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities",Integer.class));assertEquals(15,jdbc.queryForObject("SELECT SUM(revision) FROM analyzed_entities",Integer.class));
    }
    @Test void nativeHashAliasesNoncanonicalIdentityAndWrongNamespaceRefuseTheWholeCategory(){
        String canonical=CatalogCandidateRecallTest.identity("agent","fictional-agent");
        jdbc.update("UPDATE analyzed_entities SET identity_hash=? WHERE id='fictional-agent'",new byte[32]);assertThrows(RuntimeException.class,this::read);
        String bad="{\"namespace\":\"github_repository\",\"key\":\"fictional-agent\"}";jdbc.update("UPDATE analyzed_entities SET identity_key=?,identity_hash=? WHERE id='fictional-agent'",bad,JdbcEntityDedup.identityHash("agent",bad));assertThrows(RuntimeException.class,this::read);
        bad=CatalogCandidateRecallTest.identity("model","fictional-agent");jdbc.update("UPDATE analyzed_entities SET identity_key=?,identity_hash=? WHERE id='fictional-agent'",bad,JdbcEntityDedup.identityHash("agent",bad));assertThrows(RuntimeException.class,this::read);
        jdbc.update("UPDATE analyzed_entities SET identity_key=?,identity_hash=? WHERE category='agent'",canonical,JdbcEntityDedup.identityHash("agent",canonical));assertThrows(RuntimeException.class,this::read);
    }
    @Test void emptyActualCategoryRequiresTheTableAndOriginalWriter(){
        jdbc.update("DELETE FROM analyzed_entities WHERE category='agent'");assertTrue(read().facts().entities().isEmpty());assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities",Integer.class));
        assertThrows(RuntimeException.class,service::capture);tx.setReadOnly(true);assertThrows(RuntimeException.class,this::read);tx.setReadOnly(false);
        jdbc.execute("DROP TABLE analyzed_entities");assertThrows(RuntimeException.class,this::read);
    }
    @Test void constructorAndUnknownCategoryNeverQuerySql(){
        var denied=org.mockito.Mockito.mock(DataSource.class);
        new CatalogCandidateHistoryService(denied,"model",clock);
        for(String category:List.of("AGENT","news_tech","fictional_unknown"))assertThrows(RuntimeException.class,()->new CatalogCandidateHistoryService(denied,category,clock));
        assertThrows(RuntimeException.class,()->new CatalogCandidateHistoryService(denied,null,clock));org.mockito.Mockito.verifyNoInteractions(denied);
    }
    @Test void originalMetadataIsBoundedBeforeTextLoadingAndInvalidFutureFactsAreNotSkipped(){
        jdbc.update("UPDATE analyzed_entities SET summary=? WHERE id='fictional-agent'","😀".repeat(4097));assertThrows(RuntimeException.class,this::read);jdbc.update("UPDATE analyzed_entities SET summary=NULL WHERE id='fictional-agent'");
        for(String change:List.of("legacy_flag=2","analysis_state='fictional_unknown'","first_recorded_at=NULL","revision=NULL","revision=-1")){
            jdbc.update("UPDATE analyzed_entities SET "+change+" WHERE id='fictional-agent'");assertThrows(RuntimeException.class,this::read);jdbc.update("UPDATE analyzed_entities SET legacy_flag=0,analysis_state='pending',first_recorded_at=?,revision=3 WHERE id='fictional-agent'",Timestamp.from(clock.instant().minusSeconds(1)));
        }
        jdbc.update("UPDATE analyzed_entities SET first_recorded_at=? WHERE id='fictional-agent'",Timestamp.from(clock.instant().plusSeconds(1)));assertThrows(RuntimeException.class,this::read);
    }
    @Test void oneCategoryLimitPlusOneRefusesWithoutTruncationOrWritingOtherCategories(){
        jdbc.update("DELETE FROM analyzed_entities WHERE category='agent'");var values=new ArrayList<Object[]>();
        for(int n=0;n<10001;n++){String id="fictional-agent-"+n,key=CatalogCandidateRecallTest.identity("agent",id);values.add(new Object[]{id,key,JdbcEntityDedup.identityHash("agent",key),Timestamp.from(clock.instant().minusSeconds(1))});}
        jdbc.batchUpdate("INSERT INTO analyzed_entities VALUES(?,'agent',?,?, 'Fictional catalog',NULL,1,?,'pending',0)",values);
        assertThrows(NewsCandidateRecall.CapacityGuarded.class,this::read);assertEquals(10004,jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities",Integer.class));
        service=service("model");assertEquals(1,read().facts().entities().size());
    }
}
