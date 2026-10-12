package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class ReportNativePreparationReplayMySqlTest extends ReportNativePreparationReplayTest {
    @Override GenerationJdbcStoreTest fixture(){var f=new GenerationJdbcStoreMySqlTest();f.fixture();return f;}
}
