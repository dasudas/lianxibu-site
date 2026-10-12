package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CatalogSourceIntentOriginMySqlTest extends CatalogSourceIntentOriginTest { @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();} }
