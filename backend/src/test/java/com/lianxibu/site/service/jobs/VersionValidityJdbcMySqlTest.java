package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class VersionValidityJdbcMySqlTest extends VersionValidityJdbcStoreTest {
    @Override @BeforeEach void setup(){base=new VersionValidityLedgerMySqlTest();base.setup();install();}
}
