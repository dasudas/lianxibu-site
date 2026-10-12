package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerExternalResolutionLedgerMySqlTest extends OwnerExternalResolutionLedgerTest {
    @Override ResultJdbcStoreTest fixture(){var value=new ManualResultProofFixture();value.fixture();return value;}
}
