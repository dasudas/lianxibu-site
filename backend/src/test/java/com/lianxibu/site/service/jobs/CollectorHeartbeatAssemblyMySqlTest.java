package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CollectorHeartbeatAssemblyMySqlTest extends CollectorHeartbeatHttpTest {
 @Override javax.sql.DataSource source(){return new CollectorHeartbeatsNativeMySqlTest().source();}
 @Override CollectorHeartbeatService heartbeatService(){return new com.lianxibu.site.config.CollectorHeartbeatConfiguration().create(f.f.db,new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.db),f.identity,f.clock);}
}
