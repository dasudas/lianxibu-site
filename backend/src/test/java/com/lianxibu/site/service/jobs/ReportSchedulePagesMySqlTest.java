package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class ReportSchedulePagesMySqlTest extends ReportSchedulePagesTest {@Override GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreMySqlTest();}}
