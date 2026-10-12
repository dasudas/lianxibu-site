package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CollectorHostMemoryAssemblyMySqlTest extends CollectorHostMemoryHttpTest {
 @Override javax.sql.DataSource source(){return new CollectorHeartbeatsNativeMySqlTest().source();}
 @Override CollectorHostMemoryService memoryService(){return new com.lianxibu.site.config.CollectorHostMemoryConfiguration().create(f.f.f.db,new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.f.db),f.identity,f.clock);}
}
