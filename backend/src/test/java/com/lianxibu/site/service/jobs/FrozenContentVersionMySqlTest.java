package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
/** Reuses the same dedicated isolated B44 result fixture; no production rows. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class FrozenContentVersionMySqlTest extends FrozenContentVersionServiceTest {
 @Override @BeforeEach void setup(){fixture=new ResultJdbcMySqlTest();fixture.fixture();install();}
}
