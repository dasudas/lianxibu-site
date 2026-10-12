package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class MemberResultDownstreamMySqlTest extends MemberResultDownstreamTest {
 @Override GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreMySqlTest();}
 @Override String jsonType(){return "JSON";}
}
