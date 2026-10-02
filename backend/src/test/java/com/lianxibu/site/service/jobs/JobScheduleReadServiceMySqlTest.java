package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class JobScheduleReadServiceMySqlTest extends JobScheduleReadServiceTest {
    @Override @BeforeEach void fixture(){
        var fixture=new GenerationJdbcStoreMySqlTest();fixture.fixture();
        source=fixture.source;jdbc=fixture.jdbc;writes=fixture.writes;store=fixture.store;
    }
}
