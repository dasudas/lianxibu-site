package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ContentReviewEvidenceMySqlTest extends ContentReviewEvidenceServiceTest {
 @Override @BeforeEach void setup(){fixture=new ResultJdbcMySqlTest();fixture.fixture();install();}
}
