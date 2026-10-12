package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDedupStageAvailabilityMySqlTest extends SourceDedupStageAvailabilityServiceTest {
 @Override SourceDedupStageProofServiceTest fixture(){return new SourceDedupStageProofServiceTest(){@Override GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreMySqlTest();}};}
}
