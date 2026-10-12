package com.lianxibu.site.service.jobs;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceStageBirthLineageMySqlTest extends SourceStageBirthLineageTest {
    @Override SourceStageBirthProofServiceTest fixture(){return new SourceStageBirthProofMySqlTest();}
}
