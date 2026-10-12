package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDedupPartialArchiveMySqlTest extends SourceDedupPartialArchiveTest {
    @Override SourceDedupResolvedStageResultTest fixture(){return new SourceDedupResolvedStageResultTest(){@Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}};}
}
