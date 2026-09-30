package com.lianxibu.site.repository.settings;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
class JdbcSettingsWritesTest {
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private JdbcSettingsWrites store;
    private SharedWriteExecutor writes;
    private static final Instant NOW=Instant.parse("2026-10-01T00:00:00Z");
    @BeforeEach void setup(){
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:settings-write-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json CLOB NOT NULL,revision BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32) NOT NULL,"
                +"status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),"
                +"original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP NOT NULL,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),"
                +"PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,1,?)","{\"watch_terms_raw\":\"Fictional old || raw; ×\",\"unknown_legacy\":{\"keep\":true}}",java.sql.Timestamp.from(NOW));
        store=new JdbcSettingsWrites(source);writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),new DataSourceTransactionManager(source));
    }
    void transaction(String key,Runnable work){writes.execute(new ActionContext(key,"fictional-settings",null),ignored->null,ctx->{work.run();return true;});}
    @Test void mappedMergeAndSeparateOperationalRevisionSurviveServiceReconstruction(){
        transaction("map",()->{
            assertEquals(2,store.update(1,0,"{\"mapping_version\":\"1\",\"follow_keywords\":[\"Fictional\"],\"operational_config_revision\":1}","{\"schema_version\":\"1\",\"rules\":[]}",NOW));
            store.pauseIntent(true,2,NOW);store.receipt("map","a".repeat(64),"fictional-owner","{\"revision\":2}",NOW);
        });
        store=new JdbcSettingsWrites(source);
        var snapshot=store.read();assertEquals(2,snapshot.preferences().revision());assertEquals(1,snapshot.operational().revision());
        var json=com.lianxibu.site.domain.protocol.CanonicalJson.parse(snapshot.preferences().valueJson());
        assertEquals("Fictional old || raw; ×",json.get("watch_terms_raw").asText());assertTrue(json.path("unknown_legacy").path("keep").asBoolean());
        assertNotNull(snapshot.pauseIntent());assertEquals("a".repeat(64),store.receipt("map").commandHash());
        assertNull(jdbc.queryForObject("SELECT expires_at FROM request_receipts",java.sql.Timestamp.class));
        transaction("ordinary",()->store.update(2,1,"{\"follow_keywords\":[\"Next fictional\"]}",null,NOW));
        assertEquals(3,store.read().preferences().revision());assertEquals(1,store.read().operational().revision());
        assertEquals("{\"revision\":2}",store.receipt("map").responseJson());
    }
    @Test void rawOverwriteAndStaleCasHaveNoSideEffects(){
        String previous=store.read().preferences().valueJson();
        assertThrows(IllegalArgumentException.class,()->transaction("raw",()->store.update(1,0,"{\"watch_terms_raw\":\"overwritten\"}",null,NOW)));
        assertThrows(JdbcSettingsWrites.Conflict.class,()->transaction("stale",()->store.update(2,0,"{}",null,NOW)));
        assertThrows(JdbcSettingsWrites.Conflict.class,()->transaction("stale-ops",()->store.update(1,1,"{}","{}",NOW)));
        assertEquals(previous,store.read().preferences().valueJson());assertNull(store.read().operational());
    }
    @Test void receiptFailureRollsBackPreferencesOperationalConfigAndPauseIntent(){
        jdbc.execute("ALTER TABLE request_receipts ALTER COLUMN response_json VARCHAR(1)");
        assertThrows(RuntimeException.class,()->transaction("failure",()->{
            store.update(1,0,"{\"follow_keywords\":[\"Fictional\"]}","{\"rules\":[]}",NOW);
            store.pauseIntent(true,2,NOW);store.receipt("failure","a".repeat(64),"fictional-owner","{\"revision\":2}",NOW);
        }));
        assertEquals(1,store.read().preferences().revision());assertNull(store.read().operational());assertNull(store.read().pauseIntent());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Long.class));
    }
    @Test void durableNoopReceiptRejectsDifferentHashAndDoesNotBumpSettings(){
        transaction("noop",()->store.receipt("noop","a".repeat(64),"fictional-owner","{\"revision\":1}",NOW));
        transaction("replay",()->store.receipt("noop","a".repeat(64),"fictional-owner","{\"revision\":1}",NOW));
        assertThrows(JdbcSettingsWrites.Conflict.class,()->transaction("forged",()->store.receipt("noop","b".repeat(64),"fictional-owner","{\"revision\":1}",NOW)));
        assertEquals(1,store.read().preferences().revision());assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Long.class));
    }
}
