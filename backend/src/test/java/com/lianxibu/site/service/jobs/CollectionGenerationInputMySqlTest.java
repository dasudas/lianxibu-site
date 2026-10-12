package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CollectionGenerationInputMySqlTest extends CollectionGenerationInputTest {
    @Override CollectionHandoffIntegrationTest fixture(){return new CollectionHandoffIntegrationMySqlTest();}
}
