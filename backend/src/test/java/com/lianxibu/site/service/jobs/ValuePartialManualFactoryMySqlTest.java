package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ValuePartialManualFactoryMySqlTest extends ValuePartialManualFactoryTest {
    @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
}
