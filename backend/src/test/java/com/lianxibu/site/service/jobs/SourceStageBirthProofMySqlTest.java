package com.lianxibu.site.service.jobs;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceStageBirthProofMySqlTest extends SourceStageBirthProofServiceTest {
    @Override GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreMySqlTest();}
}
