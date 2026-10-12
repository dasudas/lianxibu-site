package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CatalogSourceDedupPartialSealMySqlTest extends CatalogSourceDedupPartialSealTest {
 @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();}
}
