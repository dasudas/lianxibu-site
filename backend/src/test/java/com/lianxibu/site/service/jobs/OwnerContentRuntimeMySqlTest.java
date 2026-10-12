package com.lianxibu.site.service.jobs;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerContentRuntimeMySqlTest extends OwnerContentRuntimeTest {
    @Override VersionValidityJdbcStoreTest fixture(){var value=new PooledValidityMySqlFixture();value.setup();return value;}
}
