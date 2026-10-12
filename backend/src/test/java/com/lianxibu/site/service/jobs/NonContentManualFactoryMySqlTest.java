package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class NonContentManualFactoryMySqlTest extends NonContentManualFactoryTest {
    @Override ResultJdbcStoreTest fixture(){var value=new NonContentManualProofFixture();value.fixture();return value;}
}
