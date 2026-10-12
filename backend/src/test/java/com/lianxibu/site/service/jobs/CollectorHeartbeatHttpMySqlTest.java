package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CollectorHeartbeatHttpMySqlTest extends CollectorHeartbeatHttpTest {@Override javax.sql.DataSource source(){return new CollectorHeartbeatsNativeMySqlTest().source();}}
