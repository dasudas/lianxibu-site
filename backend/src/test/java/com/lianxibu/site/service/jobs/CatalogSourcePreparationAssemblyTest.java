package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.CatalogSourceProofConfiguration;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.settings.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual persisted mapped settings through the Spring assembly function; no external model call. */
class CatalogSourcePreparationAssemblyTest {
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    CatalogSourcePlanPreparationTest f;SettingsUpdateService settings;boolean allowed=true;
    void install(String category)throws Exception{
        var origin=new CatalogSourceIntentOriginTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourcePreparationAssemblyTest.this.database();}};origin.install(category);f=origin.fixture;
        String json=f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON";f.f.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");f.f.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json "+json+",revision BIGINT,updated_at TIMESTAMP(6))");
        f.f.jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,7,?)","{\"mapping_version\":\"1\",\"follow_keywords\":[\"Fictional watched\"],\"blocked_keywords\":[\"Fictional blocked\"],\"ai_paused\":true,\"default_package_id\":null,\"operational_limits\":[],\"operational_config_revision\":0}",java.sql.Timestamp.from(f.f.clock.instant()));
        settings=new SettingsUpdateService(new SettingsJdbcStore(new JdbcSettingsWrites(f.f.source),id->{throw new AssertionError("no package lookup");},change->{throw new AssertionError("no settings write");},f.f.clock),f.f.writes);
    }
    @Test void FourActualAssembliesCaptureMappedSettingsAndReplayOriginalAfterCurrentSettingsRemoval()throws Exception{
        for(String category:List.of("agent","skill","workflow","model")){install(category);var graphs=CatalogSourceProofConfiguration.assemble(f.f.source,new DataSourceTransactionManager(f.f.source),f.f.clock,settings,()->allowed);var prepared=graphs.graph(category).coordinator().prepareDedup(f.collection.intentId(),"fictional-run",0);assertEquals(new FrozenGenerationSettings(7,List.of("Fictional watched"),List.of("Fictional blocked")),prepared.plan().scope().settings());assertEquals(prepared.plan().chunks().getFirst().requestJson(),prepared.root().requestJson());assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));int rows=f.f.count("1=1");f.f.jdbc.execute("DROP TABLE runtime_settings");f.f.jdbc.execute("DROP TABLE analyzed_entities");allowed=false;graphs=CatalogSourceProofConfiguration.assemble(f.f.source,new DataSourceTransactionManager(f.f.source),f.f.clock,settings,()->allowed);assertEquals(prepared,graphs.graph(category).coordinator().prepareDedup(f.collection.intentId(),"fictional-run",0));assertEquals(rows,f.f.count("1=1"));allowed=true;}
    }
    @Test void ActualUnmappedMissingSettingsAndClosedGateCannotPrepareOrConsumeBudget()throws Exception{
        install("model");var graph=CatalogSourceProofConfiguration.assemble(f.f.source,new DataSourceTransactionManager(f.f.source),f.f.clock,settings,()->allowed).graph("model");int rows=f.f.count("1=1");f.f.jdbc.update("UPDATE runtime_settings SET value_json='{\"unknown_setting\":true}'");assertThrows(RuntimeException.class,()->graph.coordinator().prepareDedup(f.collection.intentId(),"fictional-run",0));assertEquals(rows,f.f.count("1=1"));f.f.jdbc.update("DELETE FROM runtime_settings");assertThrows(RuntimeException.class,()->graph.coordinator().prepareDedup(f.collection.intentId(),"fictional-run",0));assertEquals(rows,f.f.count("1=1"));allowed=false;assertThrows(GenerationFlowService.CapacityGuarded.class,()->graph.coordinator().prepareDedup(f.collection.intentId(),"fictional-run",0));assertEquals(rows,f.f.count("1=1"));assertEquals(0,f.f.count("record_kind='ai_attempt'"));
    }
}
