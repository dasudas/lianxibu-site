package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.sources.JdbcCollectionHandoff;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CollectionHandoffIntegrationTest {
    GenerationJdbcStoreTest f;
    GenerationJdbcStoreTest fixture() { return new GenerationJdbcStoreTest(); }
    @BeforeEach void setup() {
        f=fixture(); f.fixture();
        f.jdbc.execute("DROP TABLE IF EXISTS source_runs"); f.jdbc.execute("DROP TABLE IF EXISTS sources");
        f.jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,category VARCHAR(16))");
        f.jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),run_kind VARCHAR(32),"
                + "business_date DATE,generation BIGINT,activation_stage VARCHAR(16),config_version BIGINT,"
                + "config_snapshot_json VARCHAR(1000),manifest_hash BINARY(32),delivery_state VARCHAR(32),fetch_state VARCHAR(32),"
                + "expected_count BIGINT,accepted_count BIGINT,excluded_count BIGINT,pending_count BIGINT,revision BIGINT,job_refs_json VARCHAR(2000))");
        f.jdbc.update("INSERT INTO sources VALUES('fictional-source','news_tech')");
        f.jdbc.update("INSERT INTO source_runs VALUES('fictional-run','fictional-source','daily','2026-01-01',1,"
                + "'production',1,'{}',?,'complete','succeeded',2,2,0,0,7,'[\"fictional-prior\"]')",new byte[32]);
    }
    void handoff() {
        var service=new CollectionHandoffService(new JdbcCollectionHandoff(f.source),new CollectionIntentService(f.source,f.clock)::ensure);
        f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-handoff","fictional-run",null),k->null,a->{service.terminal("fictional-run");return true;});
    }
    @Test void restartAddsExactlyOneReferenceAndPreservesExistingReferences() {
        handoff(); handoff();
        var refs=CanonicalJson.parse(f.jdbc.queryForObject("SELECT job_refs_json FROM source_runs",String.class));
        assertEquals(2,refs.size());assertEquals("fictional-prior",refs.get(0).asText());
        assertEquals(8,f.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));
        assertEquals(1,f.count("record_kind='generation_intent'"));
        f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{\"changed\":true}'");
        assertThrows(RuntimeException.class,this::handoff);
        assertEquals(2,f.count("1=1"));
    }
    @Test void badReferencesRollbackNewIntentAndUnfinishedRunNeverHandsOff() {
        f.jdbc.update("UPDATE source_runs SET job_refs_json='{}'");
        assertThrows(RuntimeException.class,this::handoff);assertEquals(0,f.count("1=1"));
        f.jdbc.update("UPDATE source_runs SET job_refs_json='[]',pending_count=1,delivery_state='waiting_items'");
        assertThrows(RuntimeException.class,this::handoff);assertEquals(0,f.count("1=1"));
        f.jdbc.update("UPDATE source_runs SET pending_count=0,delivery_state='empty_success',expected_count=0,accepted_count=0");
        handoff();assertEquals(1,f.count("record_kind='generation_intent' AND state='waiting_input'"));
    }
    @Test void probesNeverCreateAnalysisWorkOrRequireCollectionResultFacts() {
        f.jdbc.update("UPDATE source_runs SET run_kind='probe',expected_count=NULL,config_snapshot_json=NULL,manifest_hash=NULL");
        handoff(); assertEquals(0,f.count("1=1"));
        assertEquals(7,f.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));
    }
    @ParameterizedTest @ValueSource(strings={"Asia/Tokyo","America/Los_Angeles"})
    void terminalAndAnalysisIdentityKeepOriginalBusinessDateAcrossHostZone(String zone) {
        var original=java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
            var store=new JdbcCollectionHandoff(f.source);
            var row=f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-date-read","fictional-run",null),k->null,a->store.locked("fictional-run"));
            assertEquals(java.time.LocalDate.of(2026,1,1),row.businessDate());
            handoff();var id=f.jdbc.queryForObject("SELECT id FROM jobs WHERE record_kind='generation_intent'",String.class);
            assertEquals("2026-01-01",f.jdbc.queryForObject("SELECT business_date FROM jobs WHERE id=?",String.class,id));
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));handoff();
            assertEquals(id,f.jdbc.queryForObject("SELECT id FROM jobs WHERE record_kind='generation_intent'",String.class));
            assertEquals(1,f.count("record_kind='generation_intent'"));assertEquals(1,f.count("record_kind='disposition'"));
        } finally {java.util.TimeZone.setDefault(original);}
    }
}
