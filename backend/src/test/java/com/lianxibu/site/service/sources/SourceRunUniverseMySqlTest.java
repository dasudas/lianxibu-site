package com.lianxibu.site.service.sources;

import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceRunUniverseMySqlTest extends SourceRunUniverseServiceTest {
    @Override protected DataSource database() {
        String url=System.getenv("B45_MYSQL_URL");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/fictional_[a-z0-9_]+\\?.*"))throw new IllegalStateException("owned fictional MySQL fixture required");
        return new DriverManagerDataSource(url,System.getenv().getOrDefault("V03_MYSQL_USER","root"),System.getenv().getOrDefault("V03_MYSQL_PASSWORD",""));
    }
}
