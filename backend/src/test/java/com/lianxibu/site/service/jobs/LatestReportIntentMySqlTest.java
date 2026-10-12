package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class LatestReportIntentMySqlTest extends LatestReportIntentTest {@Override GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreMySqlTest();}}
