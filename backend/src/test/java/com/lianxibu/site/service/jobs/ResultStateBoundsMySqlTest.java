package com.lianxibu.site.service.jobs;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ResultStateBoundsMySqlTest extends ResultStateBoundsTest {
    @Override ResultJdbcStoreTest fixture(){return new ResultJdbcMySqlTest();}
}
