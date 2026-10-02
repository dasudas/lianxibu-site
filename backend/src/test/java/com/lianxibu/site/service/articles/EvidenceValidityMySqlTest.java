package com.lianxibu.site.service.articles;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="B53_MYSQL",matches="true")
class EvidenceValidityMySqlTest extends EvidenceValidityServiceTest {
    @Override @BeforeEach void setup(){String url=System.getenv("B53_MYSQL_URL");if(url==null || !url.startsWith("jdbc:mysql://127.0.0.1:13336/fictional_b53_reviews?"))throw new IllegalArgumentException("owned fictional schema required");install(new DriverManagerDataSource(url,"root",""),true);}
}
