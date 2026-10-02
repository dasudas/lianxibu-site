package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class VersionValidityLedgerMySqlTest extends VersionValidityLedgerServiceTest {
    @Override @BeforeEach void setup(){fixture=new ResultJdbcMySqlTest();fixture.fixture();install();}
}
