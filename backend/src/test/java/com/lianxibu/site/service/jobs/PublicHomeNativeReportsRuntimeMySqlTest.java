package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class PublicHomeNativeReportsRuntimeMySqlTest extends PublicHomeNativeReportsRuntimeTest {
    @Override VersionValidityJdbcStoreTest fixture(){var value=new PooledValidityMySqlFixture();value.setup();return value;}
}
