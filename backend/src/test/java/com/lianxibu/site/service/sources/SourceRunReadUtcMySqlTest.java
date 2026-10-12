package com.lianxibu.site.service.sources;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceRunReadUtcMySqlTest extends SourceRunReadUtcTest {
    @Override @org.junit.jupiter.api.BeforeEach void setup(){super.setup();jdbc.execute("ALTER TABLE source_runs MODIFY barrier_closed_at DATETIME(6)");}
    @Override SourceRunLeaseServiceTest fixture(){return OwnerRunCreateServiceMySqlTest.mysqlFixture();}
}
