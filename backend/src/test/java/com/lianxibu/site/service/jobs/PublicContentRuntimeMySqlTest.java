package com.lianxibu.site.service.jobs;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Inherits the fixture's exact dedicated loopback database guard. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class PublicContentRuntimeMySqlTest extends PublicContentRuntimeTest {
    @Override VersionValidityJdbcStoreTest fixture(){var value=new PooledValidityMySqlFixture();value.setup();return value;}
}
