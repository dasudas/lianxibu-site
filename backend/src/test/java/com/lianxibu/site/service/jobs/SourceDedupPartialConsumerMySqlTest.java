package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDedupPartialConsumerMySqlTest extends SourceDedupPartialConsumerTest {
    @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
    @Override SourceDedupDependencyServiceTest legacy(){return new SourceDedupDependencyMySqlTest();}
}
