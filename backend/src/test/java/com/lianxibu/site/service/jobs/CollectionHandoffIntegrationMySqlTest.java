package com.lianxibu.site.service.jobs;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CollectionHandoffIntegrationMySqlTest extends CollectionHandoffIntegrationTest {
    @Override GenerationJdbcStoreTest fixture() { return new GenerationJdbcStoreMySqlTest(); }
}
