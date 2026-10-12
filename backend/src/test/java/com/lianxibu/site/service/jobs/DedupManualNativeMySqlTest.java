package com.lianxibu.site.service.jobs;
class DedupManualNativeMySqlTest extends DedupManualNativeTest {
    @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
}
