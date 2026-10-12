package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CollectorHeartbeatServiceMySqlTest extends CollectorHeartbeatServiceTest {@Override javax.sql.DataSource source(){return new CollectorHeartbeatsNativeMySqlTest().source();}}
