package com.lianxibu.site.service.jobs;
class SourcePartCapturedPreparationMySqlTest extends SourcePartCapturedPreparationTest {
    @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
}
