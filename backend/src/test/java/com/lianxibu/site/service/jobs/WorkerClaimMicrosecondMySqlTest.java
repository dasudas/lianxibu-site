package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class WorkerClaimMicrosecondMySqlTest extends WorkerClaimMicrosecondTest { @Override GenerationJdbcStoreTest database(){return new GenerationJdbcStoreMySqlTest();} }
