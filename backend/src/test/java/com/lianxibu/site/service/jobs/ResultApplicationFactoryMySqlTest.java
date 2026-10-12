package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ResultApplicationFactoryMySqlTest extends ResultApplicationFactoryTest {
 @Override ResultJdbcStoreTest fixture(){return new ResultJdbcMySqlTest();}
}
