package com.lianxibu.site.service.content;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B51_MYSQL",matches="true")
class ContentComparisonVersionMySqlTest extends ContentComparisonVersionTest {
    @Override @BeforeEach void setup(){f=new GeneratedContentMySqlTest();f.fixture();ready();}
}
