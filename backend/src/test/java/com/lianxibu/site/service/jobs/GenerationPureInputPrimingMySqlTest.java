package com.lianxibu.site.service.jobs;
/** Exact shared owned MySQL fixture and original one-second writer. */
class GenerationPureInputPrimingMySqlTest extends GenerationPureInputPrimingTest {
    @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
}
