package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDedupPartialDependencyServiceMySqlTest extends SourceDedupPartialDependencyServiceTest {
    @Override SourceDedupPartialAvailabilityTest fixture(){return new SourceDedupPartialAvailabilityMySqlTest();}
}
