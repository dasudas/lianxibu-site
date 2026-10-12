package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ReportPeriodInputComponentMySqlTest extends ReportPeriodInputComponentTest {
    @Override ReportOriginalCompletionTest fixture(){return new ReportOriginalCompletionMySqlTest(){@Override ResultJdbcStoreTest fixture(){var f=(NativeFixture)super.fixture();f.logicalKeyOverride="report:daily:2026-01-01";return f;}};}
}
