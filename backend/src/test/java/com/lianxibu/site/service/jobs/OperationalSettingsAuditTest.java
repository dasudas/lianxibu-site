package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.settings.*;
import com.lianxibu.site.service.settings.SettingsService.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperationalSettingsAuditTest {
    static final Actor OWNER=new Actor("fictional-owner","owner");
    OperationalPersistenceFixture fixture;SettingsJdbcStore store;SettingsUpdateService service;
    @BeforeEach void setup(){
        fixture=new OperationalPersistenceFixture();
        fixture.jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32) NOT NULL,status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP NOT NULL,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        fixture.jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,1,?)","{\"watch_terms_raw\":\"Fictional original\",\"pause_new_jobs\":false}",java.sql.Timestamp.from(fixture.clock.instant()));
        rebuild();
    }
    void rebuild(){
        store=new SettingsJdbcStore(new JdbcSettingsWrites(fixture.source),id->true,new OperationalSettingsAudit(new JdbcJobRecords(fixture.source)),fixture.clock);
        service=new SettingsUpdateService(store,fixture.writes);
    }
    PreferencesWrite configure(){return new PreferencesWrite(1,List.of("Fictional mapped"),List.of(),true,null,MonitoringFixtures.complete(),"Fictional configuration");}
    Preferences write(PreferencesWrite command,String key){return service.write(OWNER,command,key,"fictional-csrf","https://site.example.invalid");}
    long count(String table){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    void assertRolledBack(){
        var snapshot=new JdbcSettingsWrites(fixture.source).read();assertEquals(1,snapshot.preferences().revision());
        assertNull(snapshot.operational());assertNull(snapshot.pauseIntent());assertEquals(0,count("jobs"));assertEquals(0,count("request_receipts"));
    }
    @Test void auditAndPermanentReceiptSurviveRebuildWithoutRepeatingConfiguration(){
        var first=write(configure(),"configure");assertEquals(2,first.revision());assertEquals(1,count("jobs"));assertEquals(1,count("request_receipts"));
        var audit=fixture.jdbc.queryForMap("SELECT target_type,operator_id,reason,action_payload,call_count FROM jobs");
        assertEquals("ops_rule",audit.get("TARGET_TYPE"));assertEquals(OWNER.userId(),audit.get("OPERATOR_ID"));assertEquals(0,((Number)audit.get("CALL_COUNT")).intValue());
        var payload=com.lianxibu.site.domain.protocol.CanonicalJson.parse(fixture.jdbc.queryForObject("SELECT action_payload FROM jobs",String.class));
        assertEquals("ops_configuration_change",payload.get("record_type").asText());assertEquals(0,payload.get("before").size());assertEquals(10,payload.get("after").size());assertEquals(1,payload.get("operational_revision").asLong());
        rebuild();assertEquals(first,write(configure(),"configure"));assertEquals(1,count("jobs"));
        write(new PreferencesWrite(2,List.of("Fictional changed keyword"),List.of(),true,null,first.operationalLimits()),"ordinary");
        assertEquals(1,count("jobs"));assertEquals(1,store.operationalConfiguration().revision());
        var changed=new ArrayList<>(first.operationalLimits());var old=changed.get(3);var parameters=new HashMap<>(old.ruleParameters());parameters.put("protect_free_percent","4");
        changed.set(3,new OperationalRule(old.ruleId(),old.warningThreshold(),old.criticalThreshold(),old.recoveryThreshold(),old.notificationSecretRef(),old.notificationConfigured(),parameters));
        write(new PreferencesWrite(3,List.of("Fictional changed keyword"),List.of(),true,null,changed,"Fictional guarded change"),"reconfigure");
        assertEquals(2,count("jobs"));assertEquals(3,count("request_receipts"));assertEquals(2,store.operationalConfiguration().revision());
        assertEquals(first,write(configure(),"configure"));assertEquals(2,count("jobs"));
    }
    @Test void auditConstraintFailureRollsBackAllSettingsAndReceipt(){
        fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT reject_ops_configuration CHECK(target_type IS NULL OR target_type<>'ops_rule')");
        assertThrows(RuntimeException.class,()->write(configure(),"audit-fault"));assertRolledBack();
    }
    @Test void finalReceiptFailureAlsoRollsBackAlreadyInsertedAudit(){
        fixture.jdbc.execute("ALTER TABLE request_receipts ALTER COLUMN response_json VARCHAR(1)");
        assertThrows(RuntimeException.class,()->write(configure(),"receipt-fault"));assertRolledBack();
    }
}
