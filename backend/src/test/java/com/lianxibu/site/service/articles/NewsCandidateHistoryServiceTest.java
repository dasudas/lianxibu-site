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

class NewsCandidateHistoryServiceTest {
    DataSource source;JdbcTemplate jdbc;TransactionTemplate tx;NewsCandidateHistoryService service;
    final Clock clock=Clock.fixed(NewsCandidateRecallTest.CUT,ZoneOffset.UTC);
    DataSource source(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:candidate-history-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");return ds;}
    @BeforeEach void setup(){source=source();jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),identity_key TEXT,identity_hash BINARY(32),title VARCHAR(512),summary TEXT,revision BIGINT,first_recorded_at TIMESTAMP(6),analysis_state VARCHAR(32),legacy_flag INT)");
        insert("fictional-b","news_tech",1,"Fictional Java release");insert("fictional-a","news_tech",0,"Fictional model release");insert("fictional-other","agent",0,"Fictional other category");service=service();}
    NewsCandidateHistoryService service(){return new NewsCandidateHistoryService(new JdbcNewsCandidateHistory(source),clock);}
    void insert(String id,String category,int legacy,String title){String key=new String(CanonicalJson.bytes(CanonicalJson.parse(NewsCandidateRecallTest.identity(id))),java.nio.charset.StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO analyzed_entities VALUES(?,?,?,?,?,NULL,3,?,'pending',?)",id,category,key,JdbcEntityDedup.identityHash(category,key),title,Timestamp.from(clock.instant().minusSeconds(1)),legacy);}
    NewsCandidateRecall.History read(){return tx.execute(a->service.capture());}
    @Test void actualMetadataAndOneCapturedPointSurviveRestartWithoutWritesOrCrossCategoryRows(){
        jdbc.update("UPDATE analyzed_entities SET summary='Fictional retained summary' WHERE id='fictional-a'");var history=read();assertEquals(List.of("fictional-a","fictional-b"),history.entities().stream().map(NewsCandidateRecall.Entity::id).toList());
        assertEquals(clock.instant(),history.cutoff());assertEquals("Fictional retained summary",history.entities().getFirst().summary());assertTrue(history.entities().getLast().legacy());
        for(var entity:history.entities()){assertEquals(clock.instant(),entity.metadataAsOf());assertEquals(3,entity.revision());}
        service=service();assertEquals(history,read());assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities",Integer.class));assertEquals(9,jdbc.queryForObject("SELECT SUM(revision) FROM analyzed_entities",Integer.class));
    }
    @Test void emptyActualTableIsDistinctFromMissingSchemaAndTransactions(){
        assertThrows(RuntimeException.class,service::capture);tx.setReadOnly(true);assertThrows(RuntimeException.class,this::read);tx.setReadOnly(false);
        jdbc.update("DELETE FROM analyzed_entities");assertTrue(read().entities().isEmpty());jdbc.execute("DROP TABLE analyzed_entities");assertThrows(RuntimeException.class,this::read);
    }
    @Test void originalIdentityHashNoncanonicalKeysAndDuplicateIdentityRefuseTheWholeCapture(){
        jdbc.update("UPDATE analyzed_entities SET identity_hash=? WHERE id='fictional-a'",new byte[32]);assertThrows(RuntimeException.class,this::read);
        String key=NewsCandidateRecallTest.identity("fictional-a");jdbc.update("UPDATE analyzed_entities SET identity_key=?,identity_hash=? WHERE id='fictional-a'",key,JdbcEntityDedup.identityHash("news_tech",key));assertThrows(RuntimeException.class,this::read);
        key=new String(CanonicalJson.bytes(CanonicalJson.parse(key)),java.nio.charset.StandardCharsets.UTF_8);jdbc.update("UPDATE analyzed_entities SET identity_key=?,identity_hash=? WHERE category='news_tech'",key,JdbcEntityDedup.identityHash("news_tech",key));assertThrows(RuntimeException.class,this::read);
    }
    @Test void oversizedOrUnknownMetadataAndFutureRecordingsAreNeverSilentlySkipped(){
        jdbc.update("UPDATE analyzed_entities SET summary=? WHERE id='fictional-a'","😀".repeat(4097));assertThrows(RuntimeException.class,this::read);jdbc.update("UPDATE analyzed_entities SET summary=NULL WHERE id='fictional-a'");
        for(String change:List.of("legacy_flag=2","analysis_state='fictional_unknown'","first_recorded_at=NULL","revision=NULL")){
            jdbc.update("UPDATE analyzed_entities SET "+change+" WHERE id='fictional-a'");assertThrows(RuntimeException.class,this::read);jdbc.update("UPDATE analyzed_entities SET legacy_flag=0,analysis_state='pending',first_recorded_at=?,revision=3 WHERE id='fictional-a'",Timestamp.from(clock.instant().minusSeconds(1)));
        }
        jdbc.update("UPDATE analyzed_entities SET first_recorded_at=? WHERE id='fictional-a'",Timestamp.from(clock.instant().plusSeconds(1)));assertThrows(RuntimeException.class,this::read);
    }
    @Test void limitPlusOneRefusesAnIncompleteHistoricalUniverse(){
        jdbc.update("DELETE FROM analyzed_entities");String sql="INSERT INTO analyzed_entities VALUES(?,'news_tech',?,?,?,NULL,1,?,'pending',0)";
        var values=new ArrayList<Object[]>();for(int n=0;n<10001;n++){
            String id="fictional-"+n;String key=new String(CanonicalJson.bytes(CanonicalJson.parse(NewsCandidateRecallTest.identity(id))),java.nio.charset.StandardCharsets.UTF_8);
            values.add(new Object[]{id,key,JdbcEntityDedup.identityHash("news_tech",key),"Fictional entity",Timestamp.from(clock.instant().minusSeconds(1))});
        }jdbc.batchUpdate(sql,values);assertThrows(NewsCandidateRecall.CapacityGuarded.class,this::read);assertEquals(10001,jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities",Integer.class));
    }
}
