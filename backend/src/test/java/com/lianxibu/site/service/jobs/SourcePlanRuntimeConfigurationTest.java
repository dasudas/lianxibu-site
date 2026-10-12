package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.settings.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class SourcePlanRuntimeConfigurationTest {
    NewsSourcePlanPreparationTest f;SettingsUpdateService settings;OperationalInputGate gate;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {f=new NewsSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return SourcePlanRuntimeConfigurationTest.this.database();}};f.setup();settings();}
    void settings(){
        String json=f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON";f.f.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");f.f.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json "+json+",revision BIGINT,updated_at TIMESTAMP(6))");
        f.f.jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,9,?)","{\"mapping_version\":\"1\",\"follow_keywords\":[\"Fictional watched\"],\"blocked_keywords\":[],\"ai_paused\":true,\"default_package_id\":null,\"operational_limits\":[],\"operational_config_revision\":0}",java.sql.Timestamp.from(f.f.clock.instant()));
        settings=new SettingsUpdateService(new SettingsJdbcStore(new JdbcSettingsWrites(f.f.source),id->{throw new AssertionError("no package lookup");},change->{throw new AssertionError("no settings write");},f.f.clock),f.f.writes);gate=mock(OperationalInputGate.class);when(gate.preparationAllowed()).thenReturn(true);
    }
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(NewsSourcePreparationConfiguration.class).withBean(DataSource.class,()->f.f.source).withBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(f.f.source)).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true","site.collector.enabled=true","site.execution.preparation.enabled=true");}
    <T>T readonly(java.util.function.Supplier<T> work){var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(f.f.source));tx.setReadOnly(true);return tx.execute(s->work.get());}
    @Test void configuredDedupFreezesMappedOriginalAndReplaysItsSinglePreparedPart(){
        context().run(c->{assertNull(c.getStartupFailure());var coordinator=c.getBean(SourcePlanPreparationCoordinator.class);var value=coordinator.prepareDedup(f.collection.intentId(),"fictional-run",0);assertEquals(9,value.plan().scope().settings().revision());assertEquals(java.util.List.of("Fictional watched"),value.plan().scope().settings().followKeywords());var progress=readonly(()->c.getBean(SourceStagePreparationService.class).progress(f.collection.intentId()));assertTrue(progress.complete());assertEquals(1,progress.nextPart());assertEquals(value.plan(),progress.plan());int count=f.f.count("1=1");when(gate.preparationAllowed()).thenReturn(false);f.f.jdbc.update("UPDATE runtime_settings SET value_json='{}',revision=10");assertEquals(value,coordinator.prepareDedup(f.collection.intentId(),"fictional-run",0));assertEquals(count,f.f.count("1=1"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));});
    }
    @Test void configuredCompleteP1HandoffFeedsOriginalValueV2Preparation() throws Exception {
        var x=new SourceValueIntentHandoffTest(){@Override GenerationJdbcStoreTest database(){return SourcePlanRuntimeConfigurationTest.this.database();}};x.setup();f=x.f.p1;settings();
        context().run(c->{assertNull(c.getStartupFailure());var relation=c.getBean(SourceValueIntentService.class).handoff(x.ref);var coordinator=c.getBean(SourcePlanPreparationCoordinator.class);var value=coordinator.prepareValue(relation.p2Owner(),"fictional-run",x.ref,0);assertEquals(9,value.plan().scope().settings().revision());assertEquals("2",CanonicalJson.parse(value.root().snapshotJson()).get(FrozenApplicationContext.FIELD).get("value_result_version").asText());assertEquals("site-value-material-v2",CanonicalJson.parse(value.root().requestJson()).get("prompt_version").asText());assertTrue(readonly(()->c.getBean(SourceStagePreparationService.class).progress(relation.p2Owner())).complete());int count=f.f.count("1=1");when(gate.preparationAllowed()).thenReturn(false);f.f.jdbc.update("UPDATE runtime_settings SET value_json='{}',revision=10");assertEquals(value,coordinator.prepareValue(relation.p2Owner(),"fictional-run",x.ref,0));assertEquals(count,f.f.count("1=1"));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));});
    }
    @Test void configuredGateRefusesNewPlanAndRoot(){context().run(c->{assertNull(c.getStartupFailure());when(gate.preparationAllowed()).thenReturn(false);assertThrows(RuntimeException.class,()->c.getBean(SourcePlanPreparationCoordinator.class).prepareDedup(f.collection.intentId(),"fictional-run",0));assertEquals(0,f.f.count("target_type='source_stage_plan' OR record_kind IN ('logical_task','ai_attempt')"));});}
}
