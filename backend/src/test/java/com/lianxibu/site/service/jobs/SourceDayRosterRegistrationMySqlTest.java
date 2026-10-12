package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.SourceDayRosterRegistrationConfiguration;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import java.sql.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayRosterRegistrationMySqlTest {
    final LocalDate day=LocalDate.of(2026,9,20);final Instant now=Instant.parse("2026-09-20T16:10:00.000123Z");
    GenerationJdbcStoreMySqlTest fixture;SourceDayRosterRegistrationService registry;
    @BeforeEach void setup(){
        fixture=new GenerationJdbcStoreMySqlTest();fixture.fixture();fixture.jdbc.execute("DROP TABLE IF EXISTS sources");
        fixture.jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,category VARCHAR(32),revision BIGINT,config_revision BIGINT,activation_stage VARCHAR(32),state VARCHAR(32),config_valid_until DATETIME(6),draft_config_json JSON,effective_config_json JSON)");
        registry=new SourceDayRosterRegistrationConfiguration().create(fixture.source,new DataSourceTransactionManager(fixture.source),Clock.fixed(now,ZoneOffset.UTC));
    }
    private void rows(int count){fixture.jdbc.batchUpdate("INSERT INTO sources VALUES(?,NULL,0,0,'draft','disabled',NULL,NULL,NULL)",new BatchPreparedStatementSetter(){
        public int getBatchSize(){return count;}public void setValues(PreparedStatement statement,int i)throws SQLException{statement.setString(1,"fictional-directory-"+i);}
    });}
    @Test void wholeBoundedThreeThousandDirectoryUsesOriginalOneSecondWriterAndReplaysAfterSourcesDisappear(){
        rows(3000);var frozen=registry.freeze(day);assertEquals(3000,frozen.evidence().sourceCount());assertEquals(3000,frozen.evidence().sources().size());assertEquals(now,frozen.evidence().capturedAt());
        assertTrue(frozen.evidence().metadataJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<SourceDayRoster.MAX_METADATA_BYTES);
        assertTrue(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='snapshot_part'",Integer.class)>3);
        int rows=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);fixture.jdbc.execute("DROP TABLE sources");
        Clock missing=org.mockito.Mockito.mock(Clock.class);var restarted=new SourceDayRosterRegistrationConfiguration().create(fixture.source,new DataSourceTransactionManager(fixture.source),missing);
        assertEquals(frozen.evidence().hash(),restarted.freeze(day).evidence().hash());assertEquals(frozen.evidence().metadataJson(),restarted.find(day).evidence().metadataJson());
        org.mockito.Mockito.verifyNoInteractions(missing);assertEquals(rows,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(0,fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void invalidActualSourcesAndSqlFailureCannotProduceEmptyArchive(){
        rows(4);fixture.jdbc.update("UPDATE sources SET category='fictional_unknown'");assertThrows(RuntimeException.class,()->registry.freeze(day));assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        fixture.jdbc.execute("DROP TABLE sources");assertThrows(RuntimeException.class,()->registry.freeze(day));assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
    }
    @Test void actualMysqlCommitWithLostAcknowledgementIsRecoveredFromOriginalArchive(){
        rows(4);var manager=new DataSourceTransactionManager(fixture.source){boolean lost;
            @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new TransactionSystemException("fictional acknowledgement lost",new SQLException("fictional transport","08006"));}}
        };
        var registry=new SourceDayRosterRegistrationConfiguration().create(fixture.source,manager,Clock.fixed(now,ZoneOffset.UTC));var frozen=registry.freeze(day);int rows=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);
        fixture.jdbc.update("UPDATE sources SET category='fictional_unknown'");assertEquals(frozen.evidence().hash(),registry.freeze(day).evidence().hash());assertEquals(rows,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(4,frozen.evidence().sourceCount());
    }
}
