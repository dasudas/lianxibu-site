package com.lianxibu.site.service.content;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B51_MYSQL",matches="true")
class ContentApplicationVersionMySqlTest extends ContentApplicationVersionTest {
    @Override GeneratedContentServiceTest fixture(){return new GeneratedContentMySqlTest();}
}
