package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CapturedSourcePlanNativeProofMySqlTest extends CapturedSourcePlanNativeProofTest {
 @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
}
