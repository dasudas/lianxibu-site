package com.lianxibu.site.service.articles;

import java.net.URI;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NewsCandidateHistoryMySqlTest extends NewsCandidateHistoryServiceTest {
    private static com.zaxxer.hikari.HikariDataSource pool;
    @Override DataSource source(){
        String url=System.getenv("B45_MYSQL_URL");if(url==null||!url.startsWith("jdbc:mysql://"))throw new IllegalStateException("isolated schema required");var endpoint=URI.create(url.substring(5));
        if(!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b45_inputs".equals(endpoint.getPath())||endpoint.getUserInfo()!=null)throw new IllegalStateException("isolated schema required");
        if(pool==null){var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("root");config.setPassword("");config.setMaximumPoolSize(2);config.setMinimumIdle(1);config.setPoolName("fictional-candidate-tests");pool=new com.zaxxer.hikari.HikariDataSource(config);}
        new JdbcTemplate(pool).execute("DROP TABLE IF EXISTS analyzed_entities");return pool;
    }
    @AfterAll static void closeOwnedPool(){if(pool!=null){pool.close();pool=null;}}
    @Test void caseInsensitiveDatabaseMatchingCannotAcceptACategoryAlias(){
        jdbc.execute("ALTER TABLE analyzed_entities MODIFY category VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        jdbc.update("UPDATE analyzed_entities SET category='NEWS_TECH' WHERE id='fictional-a'");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,this::read);
    }
}
