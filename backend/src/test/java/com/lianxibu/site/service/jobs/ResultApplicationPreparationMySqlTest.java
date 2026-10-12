package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ResultApplicationPreparationMySqlTest extends ResultApplicationPreparationTest {
    @Override void initialize(boolean send){var owned=new PooledResultJdbcMySqlFixture();owned.fixture();source=owned.source;jdbc=owned.jdbc;writes=owned.writes;tx=owned.tx;generation=owned.generation;identity=owned.identity;worker=owned.worker;permit=owned.permit;sends=owned.sends;restart();}
}
