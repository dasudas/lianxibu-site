package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDedupPartialAvailabilityMySqlTest extends SourceDedupPartialAvailabilityTest {
    @Override SourceDedupPartialSealTest fixture(){return new SourceDedupPartialSealMySqlTest();}
}
