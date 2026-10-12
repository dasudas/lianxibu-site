package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class DispatchCollectionProofMySqlTest extends DispatchCollectionProofTest {
    @Override CollectionGenerationInputTest fixture(){return new CollectionGenerationInputMySqlTest();}
}
