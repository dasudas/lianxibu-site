package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CollectionHandoffProofMySqlTest extends CollectionHandoffProofServiceTest {
 @Override CollectionHandoffIntegrationTest fixture(){return new CollectionHandoffIntegrationMySqlTest();}
}
