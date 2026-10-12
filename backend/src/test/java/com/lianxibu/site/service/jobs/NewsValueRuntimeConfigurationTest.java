package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.settings.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class NewsValueRuntimeConfigurationTest {
    NewsValueSourcePlanPreparationTest f;SettingsUpdateService settings;OperationalInputGate gate;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {
        f=new NewsValueSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return NewsValueRuntimeConfigurationTest.this.fixture();}};f.setup();
        String json=f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON";
        f.f.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");f.f.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json "+json+",revision BIGINT,updated_at TIMESTAMP(6))");
        f.f.jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,7,?)","{\"mapping_version\":\"1\",\"follow_keywords\":[\"Fictional watched\"],\"blocked_keywords\":[\"Fictional blocked\"],\"ai_paused\":true,\"default_package_id\":null,\"operational_limits\":[],\"operational_config_revision\":0}",java.sql.Timestamp.from(f.clock.instant()));
        settings=new SettingsUpdateService(new SettingsJdbcStore(new JdbcSettingsWrites(f.f.source),id->{throw new AssertionError("no package lookup");},change->{throw new AssertionError("no settings write");},f.clock),f.f.writes);
        gate=mock(OperationalInputGate.class);when(gate.preparationAllowed()).thenReturn(true);
    }
    ApplicationContextRunner context(){
        var tx=new DataSourceTransactionManager(f.f.source);
        return new ApplicationContextRunner().withUserConfiguration(NewsSourcePreparationConfiguration.class,DispatchReadinessConfiguration.class)
            .withBean(DataSource.class,()->f.f.source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings)
            .withBean(OperationalInputGate.class,()->gate).withBean(ResultApplicationFactory.class,()->new ResultApplicationFactory(f.f.source,tx,f.clock))
            .withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true","site.collector.enabled=true","site.execution.preparation.enabled=true");
    }
    @Test void actualConfiguredProvidersFreezeMappedInputAndPrepareThenStrictlyValidateWithoutCalling(){
        context().run(c->{
            assertNull(c.getStartupFailure());assertNotNull(c.getBean(SourceDedupStageProofService.class));assertNotNull(c.getBean(SourceDedupStageAvailabilityService.class));
            var preparation=c.getBean(NewsValueSourcePlanPreparationService.class);var plan=f.p1.tx(()->preparation.prepare(f.intent,"fictional-run",f.profile));
            assertEquals(new FrozenGenerationSettings(7,List.of("Fictional watched"),List.of("Fictional blocked")),plan.scope().settings());NewsValueInputProfile.complete(CanonicalJson.parse(plan.chunks().getFirst().snapshotJson()));
            assertEquals("2",CanonicalJson.parse(plan.chunks().getFirst().snapshotJson()).get(FrozenApplicationContext.FIELD).get("value_result_version").asText());assertEquals("site-value-material-v2",CanonicalJson.parse(plan.chunks().getFirst().requestJson()).get("prompt_version").asText());
            var part=c.getBean(SourceStagePreparationService.class);var frozen=f.p1.tx(()->part.preparePart(f.intent,0,"fictional-configured-full-p2"));var dispatch=c.getBean(DispatchReadinessService.class);
            assertEquals(plan.chunks().getFirst().requestJson(),frozen.requestJson());assertTrue(f.p1.tx(()->dispatch.ready(frozen.ownerId())));
            f.f.jdbc.update("UPDATE source_run_items SET revision=revision+1 WHERE terminal_state='accepted'");assertFalse(f.p1.tx(()->dispatch.ready(frozen.ownerId())));
            f.f.jdbc.update("UPDATE runtime_settings SET value_json='{}',revision=8");int count=f.f.count("1=1");assertEquals(plan,f.p1.tx(()->preparation.prepare(f.intent,"fictional-run",null)));assertEquals(count,f.f.count("1=1"));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        });
    }
    @Test void damagedNativeObservationPreventsConfiguredPreparationAndDispatch(){
        context().run(c->{
            assertNull(c.getStartupFailure());var preparation=c.getBean(NewsValueSourcePlanPreparationService.class);f.p1.tx(()->preparation.prepare(f.intent,"fictional-run",f.profile));
            var frozen=f.p1.tx(()->c.getBean(SourceStagePreparationService.class).preparePart(f.intent,0,"fictional-configured-bad-p2"));int count=f.f.count("1=1");f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);
            assertThrows(RuntimeException.class,()->f.p1.tx(()->c.getBean(DispatchReadinessService.class).ready(frozen.ownerId())));assertThrows(RuntimeException.class,()->f.p1.tx(()->preparation.prepare(f.intent,"fictional-run",null)));assertEquals(count,f.f.count("1=1"));
        });
    }
}
