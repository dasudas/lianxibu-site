package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** All rows and inline test DDL belong to an explicitly named loopback fixture. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ResultJdbcMySqlTest extends ResultJdbcStoreTest {
 @Override @BeforeEach void fixture(){
  String url=System.getenv("B44_MYSQL_URL");if(url==null)throw new IllegalStateException("isolated result schema required");var endpoint=URI.create(url.substring(5));
  if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b44_results".equals(endpoint.getPath()))throw new IllegalStateException("dedicated loopback schema required");
  source=new DriverManagerDataSource(url,"root","");jdbc=new JdbcTemplate(source);assertEquals("8.0.36",jdbc.queryForObject("SELECT VERSION()",String.class));assertEquals("READ-COMMITTED",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
  for(String table:new String[]{"contents","article_revisions","jobs","worker_lock"})jdbc.execute("DROP TABLE IF EXISTS "+table);
  var fixture=new GenerationJdbcStoreMySqlTest();fixture.source=source;fixture.jdbc=jdbc;fixture.schema();generation=fixture;setup(true);
 }
}
