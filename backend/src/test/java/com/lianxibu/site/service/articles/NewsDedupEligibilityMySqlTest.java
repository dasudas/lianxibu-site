package com.lianxibu.site.service.articles;

import java.net.URI;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NewsDedupEligibilityMySqlTest extends NewsDedupEligibilityServiceTest {
    @Override DataSource source() {
        String url=System.getenv("B45_MYSQL_URL");if(url==null)throw new IllegalStateException("isolated test database required");var endpoint=URI.create(url.substring(5));
        if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b45_inputs".equals(endpoint.getPath()))throw new IllegalStateException("dedicated loopback schema required");
        var source=new DriverManagerDataSource(url,"root","");var jdbc=new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS source_run_items");jdbc.execute("DROP TABLE IF EXISTS source_runs");return source;
    }
    @Override String jsonType(){return "JSON";}
}
