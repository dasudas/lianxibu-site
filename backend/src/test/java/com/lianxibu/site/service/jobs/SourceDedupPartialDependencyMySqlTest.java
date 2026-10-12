package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDedupPartialDependencyMySqlTest extends SourceDedupPartialDependencyTest {
    @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
    @Override SourceDedupPartialAvailabilityTest availabilityFixture(){return new SourceDedupPartialAvailabilityMySqlTest();}
}
