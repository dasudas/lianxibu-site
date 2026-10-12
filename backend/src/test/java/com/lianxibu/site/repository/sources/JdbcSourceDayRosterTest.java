package com.lianxibu.site.repository.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

class JdbcSourceDayRosterTest {
    private static final Instant NOW=Instant.parse("2026-09-20T16:10:00.000001Z");
    private JdbcDataSource data;private JdbcTemplate sql;private TransactionTemplate reads;
    @BeforeEach void setup(){
        data=new JdbcDataSource();data.setURL("jdbc:h2:mem:day-roster-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");sql=new JdbcTemplate(data);
        sql.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,category VARCHAR(32),revision BIGINT,config_revision BIGINT,activation_stage VARCHAR(32),state VARCHAR(32),config_valid_until TIMESTAMP(6),draft_config_json CLOB,effective_config_json CLOB)");
        reads=new TransactionTemplate(new DataSourceTransactionManager(data));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private void insert(String id,String category,String body,Instant valid){
        sql.update(connection->{var ps=connection.prepareStatement("INSERT INTO sources VALUES(?,?,0,3,'draft','disabled',?,NULL,?)");
            ps.setString(1,id);ps.setString(2,category);ps.setTimestamp(3,valid==null?null:Timestamp.from(valid),Calendar.getInstance(TimeZone.getTimeZone("UTC")));ps.setString(4,body);return ps;});
    }
    private SourceDayRoster.Evidence read(){return reads.execute(s->new JdbcSourceDayRoster(data,Clock.fixed(NOW,ZoneOffset.UTC)).read());}
    @Test void returnsWholeDirectoryIncludingUnassignedDisabledAndExactConfigurationHash() throws Exception {
        String body="{\"fixture\":\"🟣\"}";
        insert("fictional-d",null,null,null);insert("fictional-c",null,null,null);insert("fictional-b",null,null,null);insert("fictional-a","agent",body,NOW.plusSeconds(10));
        var value=read();assertNotNull(value);assertEquals(4,value.sourceCount());assertEquals(NOW,value.capturedAt());
        assertEquals(3,value.sources().stream().filter(s->s.category()==null).count());
        var a=value.sources().getFirst();assertTrue(a.effectivePresent());assertFalse(a.draftPresent());assertEquals(NOW.plusSeconds(10),a.configValidUntil());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))),a.effectiveConfigHash());
        assertEquals("disabled",a.legacyState());assertFalse(value.metadataJson().contains("🟣"));
    }
    @Test void actualEmptyDirectoryIsEvidenceAndMissingTableOrCorruptRowIsUnknown() {
        assertEquals(0,read().sourceCount());insert("fictional",null,null,null);
        sql.update("UPDATE sources SET revision=NULL");assertNull(read());sql.update("UPDATE sources SET revision=0,state=NULL");assertNull(read());
        sql.execute("DROP TABLE sources");assertNull(read());
    }
    @Test void constructionAndWrongTransactionBoundaryTouchNeitherSqlNorClock() {
        DataSource untouched=mock(DataSource.class);Clock clock=mock(Clock.class);var provider=new JdbcSourceDayRoster(untouched,clock);
        verifyNoInteractions(untouched,clock);assertThrows(IllegalStateException.class,provider::read);
        try{
            TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);assertThrows(IllegalStateException.class,provider::read);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThrows(IllegalStateException.class,provider::read);verifyNoInteractions(untouched,clock);
        }finally{TransactionSynchronizationManager.clear();}
    }
    @Test void perConfigurationAndAggregateBytesRejectCapacityWithoutTruncation() {
        insert("fictional-large",null," ".repeat(65537),null);assertThrows(SourceDayRoster.CapacityExceeded.class,this::read);
        sql.update("DELETE FROM sources");String body="\""+"x".repeat(65534)+"\"";
        for(int i=0;i<17;i++)insert("fictional-"+i,null,body,null);
        assertThrows(SourceDayRoster.CapacityExceeded.class,this::read);
    }
    @Test void utcDatetimeMicrosecondsDoNotFollowJvmDefaultTimezone() {
        TimeZone previous=TimeZone.getDefault();
        try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));insert("fictional",null,null,NOW.plusNanos(123000));
            assertEquals(NOW.plusNanos(123000),read().sources().getFirst().configValidUntil());
        }finally{TimeZone.setDefault(previous);}
    }
    @Test void unknownClockAndInvalidCategoryOrRevisionNeverBecomeZeroOrHealthy() {
        insert("fictional",null,null,null);Clock broken=mock(Clock.class);when(broken.instant()).thenThrow(new IllegalStateException("fictional clock unavailable"));
        assertNull(reads.execute(s->new JdbcSourceDayRoster(data,broken).read()));
        sql.update("UPDATE sources SET category='unknown_kind'");assertNull(read());sql.update("UPDATE sources SET category=NULL,config_revision=-1");assertNull(read());
    }
}
