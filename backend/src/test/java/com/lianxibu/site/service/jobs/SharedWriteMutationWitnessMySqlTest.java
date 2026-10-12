package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class SharedWriteMutationWitnessMySqlTest extends SharedWriteMutationWitnessTest{
 @Override Fixture fixture(){var base=new PooledResultJdbcMySqlFixture();base.fixture();base.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");base.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");return new Fixture(base.source);}
}
