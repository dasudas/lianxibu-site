package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ManualResultRefusalMySqlTest extends ManualResultRefusalTest {
    @Override ResultJdbcStoreTest fixture(boolean profile){var value=profile?new ManualResultProofFixture():new ResultJdbcMySqlTest();value.fixture();if(!profile)value.jdbc.execute("ALTER TABLE jobs ADD COLUMN report_mode VARCHAR(32)");return value;}
}
