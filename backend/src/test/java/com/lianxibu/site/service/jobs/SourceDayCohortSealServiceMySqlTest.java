package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.SourceDayCohortSealConfiguration;
import com.lianxibu.site.config.SourceDayRosterRegistrationConfiguration;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayCohortSealServiceMySqlTest extends SourceDayCohortSealServiceTest {
    @Override GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreMySqlTest();}
    @BeforeEach void sources(){
        fixture.jdbc.execute("DROP TABLE IF EXISTS sources");fixture.jdbc.execute("CREATE TABLE sources(id VARCHAR(64) PRIMARY KEY,category VARCHAR(32),revision BIGINT,config_revision BIGINT,activation_stage VARCHAR(32),state VARCHAR(32),config_valid_until DATETIME(6),draft_config_json JSON,effective_config_json JSON)");
    }
    private void configured(int count){fixture.jdbc.batchUpdate("INSERT INTO sources VALUES(?,?,2,3,'production','enabled',?,NULL,?)",new BatchPreparedStatementSetter(){
        public int getBatchSize(){return count;}public void setValues(PreparedStatement statement,int i)throws SQLException{
            statement.setString(1,"fictional-"+i);statement.setString(2,List.of("news_tech","agent","skill","workflow","model").get(i%5));statement.setTimestamp(3,Timestamp.from(now.plusSeconds(86400)),Calendar.getInstance(TimeZone.getTimeZone("UTC")));statement.setString(4,"{\"valid_until\":\"2026-09-21T16:10:00.000001Z\",\"fixture\":\"fictional\"}");
        }
    });}
    private SourceDayCohortSealService production(Clock clock){return new SourceDayCohortSealConfiguration().create(fixture.source,manager,clock);}
    @Test void productionGraphSealsThreeThousandConfiguredSourcesInOriginalShortWriterAndReplaysAfterCurrentSourcesDisappear(){
        configured(3000);var original=production(Clock.fixed(now,ZoneOffset.UTC)).seal(day);assertEquals(3000,original.plan().directory().evidence().sourceCount());assertEquals(3000,original.plan().requests().size());assertEquals(3000,original.plan().cohorts().stream().mapToInt(c->c.members().size()).sum());
        assertEquals(1,dispositions());assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind IN('logical_task','ai_attempt')",Integer.class));int rows=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);
        fixture.jdbc.execute("DROP TABLE sources");var clock=mock(Clock.class);var restarted=production(clock);assertEquals(original.plan().bundleJson(),restarted.seal(day).plan().bundleJson());assertEquals(3000,restarted.find(day).plan().requests().size());verifyNoInteractions(clock);assertEquals(rows,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
    }
    @Test void actualProductionInputFailureIsNotAnEmptySealedDay(){
        configured(4);fixture.jdbc.update("UPDATE sources SET category='fictional_unknown'");assertThrows(RuntimeException.class,()->production(Clock.fixed(now,ZoneOffset.UTC)).seal(day));assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        fixture.jdbc.execute("DROP TABLE sources");assertThrows(RuntimeException.class,()->production(Clock.fixed(now,ZoneOffset.UTC)).seal(day));assertEquals(0,dispositions());
    }
    @Test void productionGraphRecoversActualCommittedSealAfterAcknowledgementLoss(){
        configured(4);new SourceDayRosterRegistrationConfiguration().create(fixture.source,manager,Clock.fixed(now,ZoneOffset.UTC)).freeze(day);
        var tx=new DataSourceTransactionManager(fixture.source){boolean lost;
            @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new TransactionSystemException("fictional seal acknowledgement lost",new SQLException("fictional transport","08006"));}}
        };
        var service=new SourceDayCohortSealConfiguration().create(fixture.source,tx,Clock.fixed(now,ZoneOffset.UTC));var first=service.seal(day);assertEquals(4,first.plan().requests().size());assertEquals(1,dispositions());fixture.jdbc.execute("DROP TABLE sources");assertEquals(first.plan().bundleHash(),service.seal(day).plan().bundleHash());assertEquals(1,dispositions());
    }
}
