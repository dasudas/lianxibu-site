package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DispatchCollectionConfigurationTest {
    CollectionGenerationInputTest fixture(){return new CollectionGenerationInputTest();}
    @Test void configuredConsumerReadsRealReceiptBeforeAttemptingMemberSelection(){
        var f=new DispatchCollectionProofTest(){@Override CollectionGenerationInputTest fixture(){return DispatchCollectionConfigurationTest.this.fixture();}};
        f.setup();var db=f.f.fixture.f;db.jdbc.update("DELETE FROM jobs WHERE target_type='collection_handoff'");
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.source);
        var results=new com.lianxibu.site.config.ResultApplicationFactory(db.source,manager,db.clock);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.lianxibu.site.config.DispatchReadinessConfiguration.class)
                .withBean(javax.sql.DataSource.class,()->db.source)
                .withBean(org.springframework.transaction.PlatformTransactionManager.class,()->manager)
                .withBean(com.lianxibu.site.config.ResultApplicationFactory.class,()->results)
                .withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true",
                        "site.sources.enabled=true","site.execution.enabled=true")
                .run(context->{assertNull(context.getStartupFailure());var configured=context.getBean(DispatchReadinessService.class);
                    var error=assertThrows(IllegalStateException.class,()->f.ready(configured));
                    assertEquals("collection handoff proof unavailable",error.getMessage());});
    }
}
