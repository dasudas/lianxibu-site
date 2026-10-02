package com.lianxibu.site.service.content;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="B53_MYSQL",matches="true")
class ContentReviewWriteMySqlTest extends ContentReviewWriteServiceTest {
 @Override @BeforeEach void setup(){
  String url=System.getenv("B53_MYSQL_URL");if(url==null)throw new IllegalStateException("isolated review fixture required");var endpoint=URI.create(url.substring(5));if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b53_reviews".equals(endpoint.getPath()))throw new IllegalStateException("dedicated loopback database required");
  fixture=new GeneratedContentServiceTest();fixture.source=new DriverManagerDataSource(url,"root","");var jdbc=new JdbcTemplate(fixture.source);assertEquals("8.0.36",jdbc.queryForObject("SELECT VERSION()",String.class));jdbc.execute("DROP TABLE IF EXISTS contents");jdbc.execute("DROP TABLE IF EXISTS worker_lock");fixture.setup(true);install();
 }
}
