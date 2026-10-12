package com.lianxibu.site.repository.sources;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayRunClaimAdmissionServiceMySqlTest extends SourceDayRunClaimAdmissionServiceTest {
    @Override JdbcSourceDayRunRegistrationsTest fixture(){return new JdbcSourceDayRunRegistrationsMySqlTest();}
}
