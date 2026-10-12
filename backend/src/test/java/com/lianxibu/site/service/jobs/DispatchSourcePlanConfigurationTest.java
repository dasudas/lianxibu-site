package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class DispatchSourcePlanConfigurationTest {
    FrozenSourceStagePlanServiceTest fixture(){return new FrozenSourceStagePlanServiceTest();}
    ApplicationContextRunner context(GenerationJdbcStoreTest database) {
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(database.source);
        var factory=new com.lianxibu.site.config.ResultApplicationFactory(database.source,manager,database.clock);
        return new ApplicationContextRunner().withUserConfiguration(com.lianxibu.site.config.DispatchReadinessConfiguration.class)
                .withBean(DataSource.class,()->database.source).withBean(PlatformTransactionManager.class,()->manager)
                .withBean(com.lianxibu.site.config.ResultApplicationFactory.class,()->factory)
                .withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true");
    }
    @Test void actualConfiguredProviderRejectsMissingPlanBeforeReadingCurrentMemberTables() {
        var f=new DispatchSourcePlanTest(){@Override FrozenSourceStagePlanServiceTest fixture(){return DispatchSourcePlanConfigurationTest.this.fixture();}};
        f.setup();var db=f.f.fixture;db.jdbc.update("DELETE FROM jobs WHERE target_type='source_stage_plan'");int before=f.f.count();
        context(db).run(c->{assertNull(c.getStartupFailure());var service=c.getBean(DispatchReadinessService.class);
            var error=assertThrows(IllegalStateException.class,()->f.ready(service,f.f.plan.chunks().getFirst().rootId()));
            assertEquals("source partition original plan unavailable",error.getMessage());});
        assertEquals(before,f.f.count());assertEquals(0,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void actualConfiguredStrictModeRejectsOldValueInputWithoutSourcePartition() {
        var holder=fixture();var legacy=new DispatchReadinessServiceTest(){@Override GenerationJdbcStoreTest fixture(){return holder.fixture();}};
        legacy.setup();legacy.prepare(GenerationFlowService.Stage.NEWS_VALUE);int before=legacy.f.count("1=1");
        context(legacy.f).run(c->{assertNull(c.getStartupFailure());var service=c.getBean(DispatchReadinessService.class);
            var error=assertThrows(IllegalStateException.class,()->legacy.f.writes.execute(legacy.f.action(),k->null,a->service.ready(legacy.f.OWNER)));
            assertEquals("dispatch input proof unavailable",error.getMessage());});
        assertEquals(before,legacy.f.count("1=1"));assertEquals(0,legacy.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
}
