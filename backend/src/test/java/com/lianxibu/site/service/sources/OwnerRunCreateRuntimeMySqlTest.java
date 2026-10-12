package com.lianxibu.site.service.sources;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerRunCreateRuntimeMySqlTest extends OwnerRunCreateRuntimeTest {
    @Override SourceRunLeaseServiceTest fixture(){return OwnerRunCreateServiceMySqlTest.mysqlFixture();}
}
