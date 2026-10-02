package com.lianxibu.site.service.content;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
@EnabledIfEnvironmentVariable(named="B51_MYSQL",matches="true")
class GeneratedContentMySqlTest extends GeneratedContentServiceTest {
 @Override @BeforeEach void fixture(){String url=System.getenv("B51_MYSQL_URL");if(url==null)throw new IllegalStateException("isolated schema required");var endpoint=URI.create(url.substring(5));if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b51_contents".equals(endpoint.getPath()))throw new IllegalStateException("dedicated loopback schema required");source=new DriverManagerDataSource(url,"root","");var cleanup=new JdbcTemplate(source);cleanup.execute("DROP TABLE IF EXISTS contents");cleanup.execute("DROP TABLE IF EXISTS worker_lock");setup(true);}
}
