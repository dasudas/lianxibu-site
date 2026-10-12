package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.sources.JdbcManualSourceAdmissions;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.*;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.node.ObjectNode;

class ManualSourceAdmissionTest {
    final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    final Actor owner=new Actor("fixture-owner","owner");
    JdbcDataSource ds; JdbcTemplate jdbc; String config; ObjectNode proof;
    ManualSourceAdmission service;
    @BeforeEach void setup() {
        ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:manual-"+System.nanoTime()+";DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE users(id VARCHAR(64) PRIMARY KEY)");jdbc.update("INSERT INTO users VALUES('fixture-owner')");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),name VARCHAR(200),"
                +"state VARCHAR(16),config_json CLOB,probe_json CLOB,revision BIGINT,updated_at TIMESTAMP,"
                +"draft_config_json CLOB,effective_config_json CLOB,config_revision BIGINT,activation_stage VARCHAR(16),"
                +"config_valid_until TIMESTAMP,category VARCHAR(16))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),"
                +"request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) PRIMARY KEY,"
                +"receipt_kind VARCHAR(32),actor_id VARCHAR(64),source_id VARCHAR(80),scope VARCHAR(128),key_hash BINARY(32),"
                +"original_status VARCHAR(32),current_status VARCHAR(32),ack_json CLOB,result_ref_json CLOB,created_at TIMESTAMP,"
                +"revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),UNIQUE(actor_id,scope,key_hash))");
        var clock=Clock.fixed(now,ZoneOffset.UTC);
        config=new SourceAdmissionPolicy(clock).validatedConfigJson(new SourcesService.SourceConfig("fictional",
                List.of("https://feed.example.test/rss"),"guid","yesterday","fixture-v1",30,10000,
                List.of("feed.example.test"),now.plusSeconds(86400),null));
        new JdbcSourceAdmission(ds).create("fixture-source","article","Fixture","news_tech",config,now);
        proof=(ObjectNode)CanonicalJson.parse("""
            {"schema_version":"manual-source-admission-v1","action_key":"fixture-approve","source_id":"fixture-source",
            "reviewer_id":"fixture-owner","expected_revision":1,"config_version":1,"config_sha256":"HASH",
            "legacy_config_sha256":"HASH","parser_version":"fixture-v1","reviewed_at":"2026-01-01T00:00:00Z",
            "expires_at":"2026-01-01T12:00:00Z","valid_samples":3,
            "usage_basis":{"ref":"fixture-license","sha256":"HASH"},"field_mapping":{"ref":"fixture-fields","sha256":"HASH"},
            "access_policy":{"ref":"fixture-access","sha256":"HASH"},"probe_evidence":{"ref":"fixture-probe","sha256":"HASH"},
            "checks":{"tls_network":true,"root_format":true,"required_fields":true,"stable_ids":true,"time_fields":true,"usage_scope":true,"adapter_roundtrip":true}}
            """.replace("HASH",SourceIdentityMappingService.sha256(config)));
        service=service(clock);
    }
    ManualSourceAdmission service(Clock clock) {
        var tx=new DataSourceTransactionManager(ds);
        return new ManualSourceAdmission(new JdbcManualSourceAdmissions(ds),new JdbcSourceAdmission(ds),
                new SharedWriteExecutor(new JdbcSharedWriteEntry(ds),tx),clock);
    }
    ManualSourceAdmission.Manifest manifest(){return ManualSourceAdmission.Manifest.parse(CanonicalJson.bytes(proof));}
    @Test void approveRestartReplayRevokeAndNoReplayReactivation() {
        var result=service.apply(owner,"approve",manifest());
        assertEquals("production",result.activationStage());assertEquals(2,result.revision());
        assertEquals(config,jdbc.queryForObject("SELECT effective_config_json FROM sources",String.class));
        assertNull(jdbc.queryForObject("SELECT expires_at FROM request_receipts",Object.class));
        service=service(Clock.fixed(now,ZoneOffset.UTC));
        assertEquals(result,service.apply(owner,"approve",manifest()));
        ObjectNode initial=proof.deepCopy();
        proof.put("action_key","fixture-revoke");proof.put("expected_revision",2);
        assertEquals("disabled",service.apply(owner,"revoke",manifest()).activationStage());
        proof=initial;assertEquals(result,service.apply(owner,"approve",manifest()));
        assertEquals("disabled",jdbc.queryForObject("SELECT activation_stage FROM sources",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void deniesWrongActorEvidenceTimeVersionAndConflictingReplay() {
        assertThrows(RuntimeException.class,()->service.apply(new Actor("fixture-owner","user"),"approve",manifest()));
        for(String field:List.of("tls_network","root_format","required_fields","stable_ids","time_fields","usage_scope","adapter_roundtrip")) {
            ((ObjectNode)proof.get("checks")).put(field,false);
            assertThrows(RuntimeException.class,()->service.apply(owner,"approve",manifest()));
            ((ObjectNode)proof.get("checks")).put(field,true);
        }
        var original=proof.deepCopy();
        proof.put("config_sha256","0".repeat(64));assertThrows(RuntimeException.class,()->service.apply(owner,"approve",manifest()));proof=original.deepCopy();
        proof.put("config_version",2);assertThrows(RuntimeException.class,()->service.apply(owner,"approve",manifest()));proof=original.deepCopy();
        proof.put("expires_at",now.toString());assertThrows(RuntimeException.class,()->service.apply(owner,"approve",manifest()));proof=original.deepCopy();
        proof.put("reviewed_at",now.plusSeconds(1).toString());assertThrows(RuntimeException.class,()->service.apply(owner,"approve",manifest()));proof=original.deepCopy();
        proof.put("valid_samples",2);assertThrows(RuntimeException.class,()->service.apply(owner,"approve",manifest()));proof=original.deepCopy();
        service.apply(owner,"approve",manifest());
        proof.put("expires_at",now.plusSeconds(100).toString());assertThrows(RuntimeException.class,()->service.apply(owner,"approve",manifest()));
    }
    @Test void receiptFailureRollsBackSource() {
        jdbc.execute("ALTER TABLE request_receipts ADD CONSTRAINT reject_manual CHECK(scope <> 'manual-source-admission-v1')");
        assertThrows(RuntimeException.class,()->service.apply(owner,"approve",manifest()));
        assertEquals("disabled",jdbc.queryForObject("SELECT activation_stage FROM sources",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM sources",Long.class));
    }
    @Test void rejectsUnknownFieldsAndUnsafeInteger() {
        proof.put("extra",true);assertThrows(IllegalArgumentException.class,this::manifest);proof.remove("extra");
        proof.put("expected_revision",9007199254740992L);assertThrows(IllegalArgumentException.class,this::manifest);
    }
}
