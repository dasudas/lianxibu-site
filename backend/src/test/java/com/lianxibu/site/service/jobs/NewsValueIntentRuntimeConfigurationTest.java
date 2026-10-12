package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class NewsValueIntentRuntimeConfigurationTest {
    SourceValueIntentHandoffTest x;OperationalInputGate gate;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {x=new SourceValueIntentHandoffTest(){@Override GenerationJdbcStoreTest database(){return NewsValueIntentRuntimeConfigurationTest.this.database();}};x.setup();gate=mock(OperationalInputGate.class);when(gate.preparationAllowed()).thenReturn(true);}
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(NewsSourcePreparationConfiguration.class).withBean(DataSource.class,()->x.f.f.source).withBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(x.f.f.source)).withBean(SettingsUpdateService.class,()->mock(SettingsUpdateService.class)).withBean(OperationalInputGate.class,()->gate).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true","site.collector.enabled=true","site.execution.preparation.enabled=true");}
    @Test void actualConfiguredParentCompletesAndReplaysTheSameNativeChild(){
        context().run(c->{assertNull(c.getStartupFailure());var parent=c.getBean(SourceValueIntentService.class);assertNull(x.f.readonly(()->parent.readCommitted(x.ref)));int roots=x.f.f.count("record_kind IN ('logical_task','ai_attempt')");long revision=x.sourceRevision();var value=parent.handoff(x.ref);assertEquals(value.json(),x.f.readonly(()->parent.readCommitted(x.ref)).json());assertEquals("succeeded",x.state());assertEquals(1,x.children());assertEquals(1,x.relations());assertEquals(revision+1,x.sourceRevision());assertEquals(roots,x.f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals("waiting_input",x.f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,value.p2Owner()));when(gate.preparationAllowed()).thenReturn(false);int count=x.f.f.count("1=1");assertEquals(value.json(),parent.handoff(x.ref).json());assertEquals(count,x.f.f.count("1=1"));assertEquals(revision+1,x.sourceRevision());});
    }
    @Test void actualConfiguredGateAndBrokenCollectionCannotCreateNewMetadata(){
        context().run(c->{assertNull(c.getStartupFailure());var parent=c.getBean(SourceValueIntentService.class);when(gate.preparationAllowed()).thenReturn(false);assertThrows(RuntimeException.class,()->parent.handoff(x.ref));assertEquals(0,x.children());assertEquals(0,x.relations());when(gate.preparationAllowed()).thenReturn(true);x.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],x.f.p1.collection.dispositionId());assertThrows(RuntimeException.class,()->parent.handoff(x.ref));assertEquals(0,x.children());assertEquals(0,x.relations());assertEquals("queued",x.state());});
    }
}
