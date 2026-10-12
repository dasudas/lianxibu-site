package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcSourceDayRostersTest {
    final LocalDate day=LocalDate.of(2026,9,20);
    final Instant captured=Instant.parse("2026-09-20T16:10:00.000001Z"),registered=captured.plusNanos(1000);
    DataSource source;JdbcTemplate sql;TransactionTemplate tx,ro;JdbcSourceDayRosters storage;
    DataSource dataSource(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:day-rosters-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");return ds;}
    void ddl(String statement){sql.execute(statement);}
    @BeforeEach void setup(){
        source=dataSource();sql=new JdbcTemplate(source);
        ddl("CREATE TABLE jobs(id VARCHAR(64) PRIMARY KEY,business_key VARCHAR(191) UNIQUE,task_kind VARCHAR(32),target_id VARCHAR(64),state VARCHAR(32),priority_no INT,eligible_at TIMESTAMP(6),revision BIGINT,created_at TIMESTAMP(6),updated_at TIMESTAMP(6),record_kind VARCHAR(24),record_identity_hash BINARY(32) UNIQUE,call_count INT,max_attempts INT,logical_key CLOB,logical_key_hash BINARY(32),generation BIGINT,stage_part BIGINT,intent_id VARCHAR(64),task_type VARCHAR(32),business_date DATE,input_state VARCHAR(16),root_job_id VARCHAR(64),attempt_no INT,request_json CLOB,payload_json CLOB,owner_job_id VARCHAR(64),purpose VARCHAR(16),stamp_id VARCHAR(64),part_no BIGINT,payload_hash BINARY(32),UNIQUE(owner_job_id,purpose,stamp_id,part_no))");
        var manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);ro=new TransactionTemplate(manager);ro.setReadOnly(true);storage=new JdbcSourceDayRosters(source);
    }
    SourceDayRoster.Evidence evidence(int count){
        var rows=new ArrayList<SourceDayRoster.RawSource>();
        for(int i=0;i<count;i++)rows.add(new SourceDayRoster.RawSource("fictional-🟣-"+i,i%2==0?null:"agent",2,3,"draft","disabled",null,false,false,null));
        return new SourceDayRoster.Evidence(captured,count,rows);
    }
    JdbcSourceDayRosters.Frozen save(SourceDayRoster.Evidence value){return tx.execute(s->storage.save(day,value,registered));}
    JdbcSourceDayRosters.Frozen read(){return ro.execute(s->new JdbcSourceDayRosters(source).read(day));}
    @Test void completeOriginalSurvivesRestartAndSameReplayWithoutNewRows(){
        var original=evidence(800);var saved=save(original);int rows=sql.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);
        assertEquals(original.metadataJson(),read().evidence().metadataJson());assertEquals(original.hash(),read().evidence().hash());assertEquals(registered,read().registeredAt());
        assertEquals(saved.ownerId(),save(original).ownerId());assertEquals(rows,sql.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        assertTrue(sql.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='snapshot_part'",Integer.class)>2);
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind IN('logical_task','ai_attempt')",Integer.class));
        assertThrows(RuntimeException.class,()->save(evidence(799)));assertEquals(original.hash(),read().evidence().hash());
    }
    @Test void actualEmptyAndAbsentAreDifferentAndNoInvalidTransactionTouchesDatabase(){
        assertNull(read());var empty=save(evidence(0));assertEquals(0,empty.evidence().sourceCount());assertNotNull(read());
        var untouched=mock(DataSource.class);var provider=new JdbcSourceDayRosters(untouched);verifyNoInteractions(untouched);
        assertThrows(IllegalStateException.class,()->provider.read(day));assertThrows(IllegalStateException.class,()->provider.save(day,evidence(0),registered));verifyNoInteractions(untouched);
        assertThrows(IllegalStateException.class,()->ro.execute(s->storage.save(day,evidence(0),registered)));
    }
    @Test void damagedManifestChunkAndPhysicalIdentityCannotBeReconstructed(){
        save(evidence(2));sql.update("DELETE FROM jobs WHERE record_kind='snapshot_part' AND part_no=1");
        assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,()->save(evidence(2)));
        sql.update("DELETE FROM jobs");save(evidence(2));sql.update("UPDATE jobs SET payload_json='{}' WHERE record_kind='snapshot_part' AND part_no=1");assertThrows(RuntimeException.class,this::read);
        sql.update("DELETE FROM jobs");save(evidence(2));sql.update("UPDATE jobs SET id='fictional-alias' WHERE record_kind='snapshot_part' AND part_no=1");assertThrows(RuntimeException.class,this::read);
        sql.update("DELETE FROM jobs");save(evidence(2));sql.update("UPDATE jobs SET logical_key='fictional-other-day' WHERE record_kind='generation_intent'");assertThrows(RuntimeException.class,this::read);
        sql.update("DELETE FROM jobs");save(evidence(2));sql.update("DELETE FROM jobs WHERE record_kind='generation_intent'");assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,()->save(evidence(2)));
    }
    @Test void outerFailureRollsBackEveryPartAndExpiredOrChangedTimesAreRejected(){
        assertThrows(RuntimeException.class,()->tx.execute(s->{storage.save(day,evidence(5),registered);throw new IllegalStateException("fictional outer failure");}));
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        assertThrows(RuntimeException.class,()->tx.execute(s->storage.save(day,evidence(0),captured.minusNanos(1000))));
        assertThrows(RuntimeException.class,()->tx.execute(s->storage.save(day,evidence(0),Instant.parse("2026-09-20T20:30:00.000000Z"))));
        save(evidence(0));assertThrows(RuntimeException.class,()->tx.execute(s->storage.save(day,evidence(0),registered.plusNanos(1000))));
    }
}
