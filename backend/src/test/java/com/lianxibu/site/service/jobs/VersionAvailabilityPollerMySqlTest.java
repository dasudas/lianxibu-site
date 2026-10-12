package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class VersionAvailabilityPollerMySqlTest extends VersionAvailabilityPollerTest {
    @Override @BeforeEach void setup(){fixture=new ResultJdbcMySqlTest();fixture.fixture();install();}
    @Override String dropConstraint(){return "ALTER TABLE jobs DROP CHECK fictional_observer_failure";}
}
