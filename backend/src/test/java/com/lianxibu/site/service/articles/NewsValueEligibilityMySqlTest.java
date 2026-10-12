package com.lianxibu.site.service.articles;

import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NewsValueEligibilityMySqlTest extends NewsValueEligibilityServiceTest {
    @Override DataSource source(){var source=new NewsDedupEligibilityMySqlTest().source();new JdbcTemplate(source).execute("DROP TABLE IF EXISTS analyzed_entities");return source;}
    @Override String jsonType(){return "JSON";}
}
