package com.lianxibu.site.service.sources;
import com.lianxibu.site.config.*;import com.lianxibu.site.controller.collector.SourceContractTelemetryController;import com.lianxibu.site.domain.identity.NodeTokens;import com.lianxibu.site.service.identity.NodeIdentityService;
import org.springframework.context.support.GenericApplicationContext;import org.springframework.boot.autoconfigure.EnableAutoConfiguration;import org.springframework.context.annotation.Import;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceContractTelemetryAssemblyMySqlTest extends SourceContractTelemetryHttpTest {
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
 @EnableAutoConfiguration(exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
 @Import({SourceContractTelemetryConfiguration.class,SourceContractTelemetryController.class,CollectorSecurityConfiguration.class,BootstrapSecurityConfiguration.class})
 static class App{}
 @Override javax.sql.DataSource source(){return SourceContractNativeMySqlFixture.mysql();}
 @Override Class<?> appType(){return App.class;}
 @Override void install(GenericApplicationContext registry){f.f.jdbc.update("UPDATE sources SET config_valid_until=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)),f.sourceId);registry.registerBean(javax.sql.DataSource.class,()->f.f.db);registry.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.db));registry.registerBean(NodeIdentityService.class,()->new NodeIdentityService(f.nodes,new NodeTokens(new byte[32]),f.clock));}
}
