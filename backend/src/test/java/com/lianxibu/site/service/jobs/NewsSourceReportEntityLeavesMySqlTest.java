package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NewsSourceReportEntityLeavesMySqlTest extends NewsSourceReportEntityLeavesTest {
    @Override GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreMySqlTest();}
}
