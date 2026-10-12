package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.NewsSourcePreparationConfiguration;
import com.lianxibu.site.config.OperationalInputGate;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.settings.*;
import java.util.List;
import org.junit.jupiter.api.*;

class NewsSourcePreparationAssemblyTest {
    NewsSourcePlanPreparationTest f;NewsSourcePreparationConfiguration config;NewsSourcePlanPreparationService preparation;SettingsUpdateService settings;OperationalInputGate gate;
    NewsSourcePlanPreparationTest fixture(){return new NewsSourcePlanPreparationTest();}
    @BeforeEach void setup() throws Exception {
        f=fixture();f.setup();String json=f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON";
        f.f.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");f.f.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json "+json+",revision BIGINT,updated_at TIMESTAMP(6))");
        f.f.jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,7,?)","{\"mapping_version\":\"1\",\"follow_keywords\":[\"Fictional watched\"],\"blocked_keywords\":[\"Fictional blocked\"],\"ai_paused\":true,\"default_package_id\":null,\"operational_limits\":[],\"operational_config_revision\":0}",java.sql.Timestamp.from(f.f.clock.instant()));
        var store=new SettingsJdbcStore(new JdbcSettingsWrites(f.f.source),id->{throw new AssertionError("no package lookup during input capture");},change->{throw new AssertionError("no settings write");},f.f.clock);
        settings=new SettingsUpdateService(store,f.f.writes);gate=mock(OperationalInputGate.class);when(gate.preparationAllowed()).thenReturn(true);
        config=new NewsSourcePreparationConfiguration();preparation=config.newsSourcePlanPreparation(f.f.source,settings,gate);
    }
    SourcePartitionPlanner.Plan prepare(){return f.tx(()->preparation.prepare(f.collection.intentId(),"fictional-run"));}
    @Test void productionAssemblyFreezesActualMappedSettingsAndOriginalRequests(){
        var plan=prepare();assertEquals(new FrozenGenerationSettings(7,List.of("Fictional watched"),List.of("Fictional blocked")),plan.scope().settings());
        var result=f.tx(()->config.sourceStagePreparation(f.f.source).preparePart(f.collection.intentId(),0,"fictional-assembled-part"));
        assertEquals(plan.chunks().getFirst().requestJson(),result.requestJson());assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        int count=f.f.count("1=1");f.f.jdbc.update("UPDATE runtime_settings SET value_json='{}',revision=8");f.f.jdbc.execute("DROP TABLE analyzed_entities");
        preparation=config.newsSourcePlanPreparation(f.f.source,settings,gate);assertEquals(plan,prepare());assertEquals(count,f.f.count("1=1"));
    }
    @Test void unmappedOrMissingPreferencesAndCapacityRefuseWithoutRetainedPreparation(){
        int count=f.f.count("1=1");f.f.jdbc.update("UPDATE runtime_settings SET value_json='{\"watch_terms_raw\":\"Fictional || unknown\"}'");
        assertThrows(RuntimeException.class,this::prepare);assertEquals(count,f.f.count("1=1"));
        f.f.jdbc.update("DELETE FROM runtime_settings");assertThrows(RuntimeException.class,this::prepare);assertEquals(count,f.f.count("1=1"));
        when(gate.preparationAllowed()).thenReturn(false);assertThrows(GenerationFlowService.CapacityGuarded.class,this::prepare);assertEquals(count,f.f.count("1=1"));
    }
}
