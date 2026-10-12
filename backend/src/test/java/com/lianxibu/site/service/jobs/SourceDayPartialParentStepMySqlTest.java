package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayPartialParentStepMySqlTest extends SourceDayPartialParentStepTest {
    @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
}
