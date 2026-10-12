package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

/** All rows and inline test DDL belong to an explicitly named loopback fixture. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ResultJdbcMySqlTest extends ResultJdbcStoreTest {
 @Override @BeforeEach void fixture(){
  String url=System.getenv("B44_MYSQL_URL");if(url==null)throw new IllegalStateException("isolated result schema required");var endpoint=URI.create(url.substring(5));
  if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b44_results".equals(endpoint.getPath()))throw new IllegalStateException("dedicated loopback schema required");
  source=dataSource(url);jdbc=new JdbcTemplate(source);assertEquals("8.0.36",jdbc.queryForObject("SELECT VERSION()",String.class));assertEquals("READ-COMMITTED",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
  for(String table:new String[]{"contents","article_revisions","jobs","worker_lock"})jdbc.execute("DROP TABLE IF EXISTS "+table);
  var fixture=new GenerationJdbcStoreMySqlTest();fixture.source=source;fixture.jdbc=jdbc;fixture.schema();generation=fixture;setup(true);
 }
 private static com.zaxxer.hikari.HikariDataSource isolatedPool;
 private static String isolatedUrl;
 private static final class OwnedTestPool extends com.zaxxer.hikari.HikariDataSource {
  OwnedTestPool(com.zaxxer.hikari.HikariConfig configuration){super(configuration);}
  @Override public void close(){ }
  void closeOwned(){super.close();}
 }
 static synchronized com.zaxxer.hikari.HikariDataSource isolatedSource(String url){
  try{
   if(url==null||!url.startsWith("jdbc:mysql://"))throw new IllegalStateException("isolated result database required");var endpoint=URI.create(url.substring(5));
   if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b44_results".equals(endpoint.getPath())||endpoint.getUserInfo()!=null)throw new IllegalStateException("dedicated loopback result schema required");
   if(isolatedPool!=null){if(!url.equals(isolatedUrl))throw new IllegalStateException("isolated result endpoint changed");return isolatedPool;}
   var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("root");config.setPassword("");config.setMaximumPoolSize(5);config.setMinimumIdle(1);config.setConnectionTimeout(5000);config.setValidationTimeout(1000);config.setMaxLifetime(0);config.setIdleTimeout(0);config.setPoolName("fictional-b44-tests");
   var owned=new OwnedTestPool(config);isolatedPool=owned;isolatedUrl=url;Runtime.getRuntime().addShutdownHook(new Thread(owned::closeOwned,"fictional-b44-test-pool-close"));return isolatedPool;
  }catch(IllegalArgumentException malformed){throw new IllegalStateException("dedicated loopback result schema required");}
 }
 javax.sql.DataSource dataSource(String url){return isolatedSource(url);}
}
