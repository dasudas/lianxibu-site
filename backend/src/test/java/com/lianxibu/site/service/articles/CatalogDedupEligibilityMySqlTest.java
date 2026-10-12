package com.lianxibu.site.service.articles;

import java.net.URI;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CatalogDedupEligibilityMySqlTest extends CatalogDedupEligibilityTest {
    private static com.zaxxer.hikari.HikariDataSource pool;
    @Override DataSource source(){
        String url=System.getenv("B45_MYSQL_URL");if(url==null||!url.startsWith("jdbc:mysql://"))throw new IllegalStateException("isolated schema required");var endpoint=URI.create(url.substring(5));
        if(!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b45_inputs".equals(endpoint.getPath())||endpoint.getUserInfo()!=null)throw new IllegalStateException("isolated schema required");
        if(pool==null){var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("root");config.setPassword("");config.setMaximumPoolSize(2);config.setMinimumIdle(1);config.setPoolName("fictional-catalog-eligibility-tests");pool=new com.zaxxer.hikari.HikariDataSource(config);}
        var jdbc=new JdbcTemplate(pool);jdbc.execute("DROP TABLE IF EXISTS source_run_items");jdbc.execute("DROP TABLE IF EXISTS source_runs");return pool;
    }
    @AfterAll static void closeOwnedPool(){if(pool!=null){pool.close();pool=null;}}
    @Override String jsonType(){return "JSON";}
    @Test void caseInsensitiveDatabaseMatchingCannotAcceptACategoryAlias(){
        jdbc.execute("ALTER TABLE source_run_items MODIFY category VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        jdbc.update("UPDATE source_run_items SET category='AGENT' WHERE id='fictional-item-0'");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,this::selection);
    }
}
