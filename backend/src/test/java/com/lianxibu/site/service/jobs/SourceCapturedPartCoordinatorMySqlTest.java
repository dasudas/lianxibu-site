package com.lianxibu.site.service.jobs;
class SourceCapturedPartCoordinatorMySqlTest extends SourceCapturedPartCoordinatorTest {
    @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
}
