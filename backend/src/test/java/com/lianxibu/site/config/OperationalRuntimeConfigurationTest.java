package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.settings.SettingsJdbcStore.OperationalConfiguration;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;
class OperationalRuntimeConfigurationTest {
    @TempDir Path directory;
    @Test void assembledGateReadsRealSettingsAndSummaryAndRefusesVersionDrift()throws Exception{
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:ops-assembly-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");var jdbc=new JdbcTemplate(db);var tx=new DataSourceTransactionManager(db);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json CLOB,revision BIGINT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE jobs(record_kind VARCHAR(24),target_type VARCHAR(32),action_key_hash BINARY(32),action_payload CLOB)");
        Path file=directory.resolve("scopes.json");Files.writeString(file,OperationalScopeCatalogTest.scopes());var catalog=OperationalScopeCatalog.load(file.toString());
        var gate=new CollectorRuntimeConfiguration().operationalInputGate(db,tx,catalog);assertFalse(gate.uploadAllowed());
        var json=JsonMapper.shared();var rules=json.createArrayNode();for(var rule:MonitoringFixtures.complete()){
            var row=rules.addObject();row.put("rule_id",rule.ruleId());row.put("warning_threshold",rule.warningThreshold());row.put("critical_threshold",rule.criticalThreshold());row.put("recovery_threshold",rule.recoveryThreshold());row.putNull("notification_secret_ref");row.put("notification_configured",false);var parameters=row.putObject("rule_parameters");rule.ruleParameters().forEach(parameters::put);
        }
        var config=json.createObjectNode();config.put("schema_version","1");config.set("rules",rules);Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        jdbc.update("INSERT INTO runtime_settings VALUES('operational_limits-v1',?,1,?)",config.toString(),java.sql.Timestamp.from(now));
        jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,2,?)","{\"mapping_version\":\"1\",\"follow_keywords\":[],\"blocked_keywords\":[],\"ai_paused\":false,\"default_package_id\":null,\"operational_config_revision\":1}",java.sql.Timestamp.from(now));
        assertFalse(gate.uploadAllowed());
        var store=new OperationalJdbcStore(new JdbcOperationalSummaries(db),new JdbcJobRecords(db),Clock.systemUTC());
        var monitor=new OperationalMonitoringService(store,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),new SharedWriteExecutor(new JdbcSharedWriteEntry(db),tx),Clock.systemUTC());
        for(Key key:catalog.scopes().capacity())monitor.sample(key,true,key.ruleId().equals("OPS-04")?new Capacity(new BigDecimal("50")):new Telemetry(0,0),now,new SharedWriteExecutor.ActionContext("fixture-"+key.ruleId(),"fixture-ops",null));
        assertTrue(gate.uploadAllowed());assertTrue(gate.preparationAllowed());assertFalse(gate.dispatchAllowed());assertFalse(gate.newRunAllowed("fictional-source"));
        Key sourceKey=catalog.scopes().sources().get("fictional-source").getFirst();monitor.sample(sourceKey,true,new ContractFailures(0,0,true),now,new SharedWriteExecutor.ActionContext("fixture-source","fixture-ops",null));assertTrue(gate.newRunAllowed("fictional-source"));
        jdbc.update("UPDATE runtime_settings SET revision=2 WHERE setting_key='operational_limits-v1'");assertFalse(gate.uploadAllowed());assertFalse(gate.newRunAllowed("fictional-source"));
        assertEquals(3L,jdbc.queryForObject("SELECT revision FROM runtime_settings WHERE setting_key='ops_summary-v1'",Long.class));assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class));
    }
}
