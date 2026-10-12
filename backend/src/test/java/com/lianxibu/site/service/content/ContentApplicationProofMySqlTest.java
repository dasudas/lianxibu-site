package com.lianxibu.site.service.content;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B51_MYSQL",matches="true")
class ContentApplicationProofMySqlTest extends ContentApplicationProofTest {
    @org.junit.jupiter.api.BeforeEach @Override void setup(){f=new GeneratedContentMySqlTest();f.fixture();ready();}
}
