package com.lianxibu.site.service.settings;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.settings.SettingsService.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
class SettingsJdbcStoreTest {
    static final Actor OWNER=new Actor("fictional-owner","owner");
    JdbcDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager tx;SharedWriteExecutor writes;
    SettingsJdbcStore store;SettingsUpdateService service;
    final List<SettingsJdbcStore.OperationalChange> audits=new ArrayList<>();
    @BeforeEach void setup(){
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:settings-consumer-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc=new JdbcTemplate(source);tx=new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json CLOB NOT NULL,revision BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32) NOT NULL,status_code INT,response_json CLOB,expires_at TIMESTAMP,"
                +"id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP NOT NULL,revision BIGINT,"
                +"request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,1,?)","{\"watch_terms_raw\":\"Fictional legacy || value\",\"blocked_terms_raw\":\"Fictional original\",\"pause_new_jobs\":false}",java.sql.Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
        writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);rebuild();
    }
    void rebuild(){store=new SettingsJdbcStore(new JdbcSettingsWrites(source),id->"fictional-published".equals(id),change->{
        assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        var pause=new JdbcSettingsWrites(source).read().pauseIntent();assertNotNull(pause);audits.add(change);
    },Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"),ZoneOffset.UTC));service=new SettingsUpdateService(store,writes);}
    Preferences write(PreferencesWrite command,String key){return service.write(OWNER,command,key,"fictional-csrf","https://site.example.invalid");}
    PreferencesWrite configure(){return new PreferencesWrite(1,List.of("Explicit fictional"),List.of(),true,"fictional-published",OperationalFixtures.complete(),"Fictional configuration");}
    @Test void explicitMappingConfigurationAndReceiptSurviveRebuildingTheConsumer(){
        assertThrows(SettingsUpdateService.Rejected.class,()->service.get(OWNER));assertFalse(store.operationalConfiguration().ready());
        Preferences updated=write(configure(),"map");assertEquals(2,updated.revision());assertEquals(1,audits.size());
        assertEquals(1,store.operationalConfiguration().revision());assertTrue(store.operationalConfiguration().ready());
        rebuild();assertEquals(updated,service.get(OWNER));assertEquals(updated,write(configure(),"map"));assertEquals(1,audits.size());
        String raw=new JdbcSettingsWrites(source).read().preferences().valueJson();
        assertEquals("Fictional legacy || value",com.lianxibu.site.domain.protocol.CanonicalJson.parse(raw).get("watch_terms_raw").asText());
        var frozen=service.freezeForNewInput();write(new PreferencesWrite(2,List.of("New fictional"),List.of(),true,"fictional-published",updated.operationalLimits()),"ordinary");
        assertEquals(List.of("Explicit fictional"),frozen.followKeywords());assertEquals(1,store.operationalConfiguration().revision());assertEquals(1,audits.size());
        assertEquals(updated,write(configure(),"map"));
    }
    @Test void badReasonStaleRevisionAndUnpublishedDefaultHaveNoPersistentEffects(){
        assertThrows(IllegalArgumentException.class,()->write(new PreferencesWrite(1,List.of(),List.of(),true,null,OperationalFixtures.complete()),"no-reason"));
        assertThrows(SettingsUpdateService.Rejected.class,()->write(new PreferencesWrite(2,List.of(),List.of(),true,null,OperationalFixtures.complete(),"Fictional"),"stale"));
        assertThrows(SettingsUpdateService.Rejected.class,()->write(new PreferencesWrite(1,List.of(),List.of(),true,"fictional-draft",OperationalFixtures.complete(),"Fictional"),"draft"));
        assertEquals(1,new JdbcSettingsWrites(source).read().preferences().revision());assertNull(new JdbcSettingsWrites(source).read().operational());assertEquals(0,audits.size());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Long.class));
    }
    @Test void auditPortFailureRollsBackPreferencesConfigurationPauseAndFinalReceipt(){
        store=new SettingsJdbcStore(new JdbcSettingsWrites(source),id->true,change->{throw new IllegalStateException("fictional audit failure");},Clock.systemUTC());
        service=new SettingsUpdateService(store,writes);assertThrows(RuntimeException.class,()->write(configure(),"fault"));
        var current=new JdbcSettingsWrites(source).read();assertEquals(1,current.preferences().revision());assertNull(current.operational());assertNull(current.pauseIntent());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Long.class));
    }
    @Test void alteredStoredVersionOrParameterMapIsUnknownWithoutDefaults(){
        write(configure(),"map");
        jdbc.update("UPDATE runtime_settings SET revision=revision+1 WHERE setting_key='operational_limits-v1'");
        assertFalse(store.operationalConfiguration().ready());assertThrows(SettingsUpdateService.Rejected.class,()->service.get(OWNER));
        jdbc.update("UPDATE runtime_settings SET revision=1,value_json=? WHERE setting_key='operational_limits-v1'","{\"schema_version\":\"1\",\"rules\":[]}");
        assertFalse(store.operationalConfiguration().ready());
    }
}
