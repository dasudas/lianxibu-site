package com.lianxibu.site.repository.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.*;
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
import org.springframework.transaction.support.TransactionTemplate;

class JdbcSourceDayRunRegistrationsTest {
    final LocalDate day=LocalDate.of(2026,9,20);final Instant registered=Instant.parse("2026-09-20T16:10:00.000001Z"),reserved=registered.plusNanos(1000),until=registered.plusSeconds(600);
    DataSource data;JdbcTemplate sql;TransactionTemplate tx,reads;JdbcSourceDayRunRegistrations store;SourceDayRunRegistration.Request request;
    DataSource dataSource(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:day-runs-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");return ds;}
    void ddl(String statement){sql.execute(statement);}
    @BeforeEach void setup()throws Exception{
        data=dataSource();sql=new JdbcTemplate(data);
        ddl("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,category VARCHAR(32),revision BIGINT,config_revision BIGINT,activation_stage VARCHAR(32),state VARCHAR(32),config_valid_until TIMESTAMP(6),draft_config_json CLOB,effective_config_json CLOB)");
        ddl("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP(6),revision BIGINT,source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,generation BIGINT,window_start TIMESTAMP(6),window_end TIMESTAMP(6),base_run_id VARCHAR(64),config_version BIGINT,config_snapshot_json CLOB,activation_stage VARCHAR(32),fetch_mode VARCHAR(32),collection_reason VARCHAR(32),delivery_state VARCHAR(32),fetch_state VARCHAR(32),analysis_state VARCHAR(32),accepted_count BIGINT,excluded_count BIGINT,pending_count BIGINT,coverage_json CLOB,job_refs_json CLOB,UNIQUE(source_id,run_kind,business_date,generation))");
        ddl("CREATE TABLE request_receipts(id VARCHAR(64) PRIMARY KEY,principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP(6),receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(64),key_hash BINARY(32),run_id VARCHAR(64),original_status VARCHAR(32),current_status VARCHAR(32),ack_json CLOB,result_ref_json CLOB,created_at TIMESTAMP(6),revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),UNIQUE(actor_id,scope,key_hash))");
        String body="{\"valid_until\":\""+until+"\",\"fixture\":\"🟣\"}";
        sql.update(c->{var ps=c.prepareStatement("INSERT INTO sources VALUES('fictional-source','agent',2,3,'production','disabled',?,NULL,?)");ps.setTimestamp(1,Timestamp.from(until),Calendar.getInstance(TimeZone.getTimeZone("UTC")));ps.setString(2,body);return ps;});
        String actual=sql.queryForObject("SELECT effective_config_json FROM sources",String.class);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8)));
        request=new SourceDayRunRegistration.Request(day,"a".repeat(64),registered,new SourceDayRoster.RawSource("fictional-source","agent",2,3,"production","disabled",until,false,true,hash),"fictional-run");
        var manager=new DataSourceTransactionManager(data);tx=new TransactionTemplate(manager);reads=new TransactionTemplate(manager);reads.setReadOnly(true);store=new JdbcSourceDayRunRegistrations(data);
    }
    SourceDayRunRegistration.Reservation reserve(){return tx.execute(s->store.reserve(request,reserved));}
    SourceDayRunRegistration.Reservation read(){return reads.execute(s->new JdbcSourceDayRunRegistrations(data).lookup(request));}
    @Test void originalRunAndPermanentReceiptCommitTogetherAndReplayWithoutCurrentSource(){
        var first=reserve();assertNotNull(first);assertEquals(1,first.generation());assertEquals(reserved,first.reservedAt());assertEquals(first,read());
        assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));assertNull(sql.queryForObject("SELECT expires_at FROM request_receipts",Timestamp.class));
        sql.update("UPDATE source_runs SET revision=9,delivery_state='complete',analysis_state='complete'");sql.execute("DROP TABLE sources");assertEquals(first,read());assertEquals(first,reserve());assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void changedMissingOrExpiredSourceCannotBeReserved(){
        assertNull(read());sql.update("UPDATE sources SET revision=7");assertNull(reserve());sql.update("UPDATE sources SET revision=2,config_revision=NULL");assertThrows(RuntimeException.class,this::reserve);
        sql.update("UPDATE sources SET config_revision=3");assertNull(tx.execute(s->store.reserve(request,until)));assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));
        sql.update("DELETE FROM sources");assertNull(reserve());
    }
    @Test void changedOriginalRequestAndCorruptCoreOrReceiptCannotReplayOrRepair(){
        reserve();var changed=new SourceDayRunRegistration.Request(day,"b".repeat(64),registered,request.source(),request.runId());assertThrows(RuntimeException.class,()->reads.execute(s->store.lookup(changed)));
        sql.update("UPDATE source_runs SET config_version=4");assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,this::reserve);
        sql.update("UPDATE source_runs SET config_version=3");sql.update("UPDATE request_receipts SET business_hash=?",new byte[32]);assertThrows(RuntimeException.class,this::read);
        sql.update("DELETE FROM request_receipts");assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,this::reserve);
    }
    @Test void outerRollbackAndInvalidTransactionNeverCommitAnything(){
        assertThrows(RuntimeException.class,()->tx.execute(s->{store.reserve(request,reserved);throw new IllegalStateException("fictional outer failure");}));assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
        var untouched=mock(DataSource.class);var storage=new JdbcSourceDayRunRegistrations(untouched);assertThrows(IllegalStateException.class,()->storage.lookup(request));assertThrows(IllegalStateException.class,()->storage.reserve(request,reserved));verifyNoInteractions(untouched);
        assertThrows(IllegalStateException.class,()->reads.execute(s->store.reserve(request,reserved)));
    }
    @Test void reservedTimeAndImmutableUtcCoreRemainExactUnderDifferentJvmTimezone(){
        var previous=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));assertEquals(reserved,reserve().reservedAt());assertEquals(reserved,read().reservedAt());}
        finally{TimeZone.setDefault(previous);}
        sql.update("UPDATE source_runs SET window_end=?",Timestamp.from(registered));assertThrows(RuntimeException.class,this::read);
    }
}
