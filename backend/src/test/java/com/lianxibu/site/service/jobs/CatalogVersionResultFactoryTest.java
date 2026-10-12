package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.ResultApplicationFactory;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual production factory composition on native original model/result/member proofs; the external model is fictional. */
class CatalogVersionResultFactoryTest extends CatalogVersionMemberCompletionTest {
    ResultApplicationFactory factory(){return new ResultApplicationFactory(f.source,f.tx,f.clock);}
    @Test void FourCategoryActualFactoryAppliesNativeVersionsAndPermanentMemberCompletionTogether(){
        for(String category:List.of("agent","skill","workflow","model")){
            completion(category,true);var application=factory().forAttempt(f.identity);assertEquals(original,application.input());f.service=application.service();
            assertEquals(Status.APPLIED,apply().status());assertEquals(1,fragments());assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(5,f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id='fictional-current-item'",Integer.class));assertEquals(DEDUP_ROOT,f.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals("free",new JdbcWorkerState(f.source).slot().state());assertEquals(1,new JdbcJobRecords(f.source).byId(DEDUP_ROOT).callCount());assertEquals(2,f.sends);
            f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-current-item'");f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");application=factory().forAttempt(f.identity);f.service=application.service();assertEquals(Status.APPLIED,apply().status());assertEquals(1,fragments());assertEquals(2,f.sends);
        }
    }
    @Test void MissingNativeVersionFrameRejectsApplicationWhileRetainingOriginalSentResponse(){
        boot("agent",true,false);var application=factory().forAttempt(f.identity);f.service=application.service();assertThrows(RuntimeException.class,this::apply);assertNotNull(new JdbcResultState(f.source).root(DEDUP_ROOT).resultHash());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals("occupied",new JdbcWorkerState(f.source).slot().state());assertEquals(2,f.sends);
    }
    @Test void ConstructingAllNativeCatalogProvidersPerformsZeroDatabaseOrNetworkIo(){
        var source=(javax.sql.DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{javax.sql.DataSource.class},(proxy,method,args)->{throw new AssertionError("factory construction must not access data source");});
        assertDoesNotThrow(()->new ResultApplicationFactory(source,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),f.clock));
    }
}
