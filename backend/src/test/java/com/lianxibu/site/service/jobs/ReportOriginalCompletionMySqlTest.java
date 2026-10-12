package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ReportOriginalCompletionMySqlTest extends ReportOriginalCompletionTest {
 @Override ResultJdbcStoreTest legacyFixture(){return new ResultJdbcMySqlTest();}
 @Override ResultJdbcStoreTest fixture(){return new NativeFixture(){
  @Override @BeforeEach void fixture(){source=ResultJdbcMySqlTest.isolatedSource(System.getenv("B44_MYSQL_URL"));jdbc=new JdbcTemplate(source);assertEquals("8.0.36",jdbc.queryForObject("SELECT VERSION()",String.class));assertEquals("READ-COMMITTED",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));for(String table:new String[]{"contents","article_revisions","jobs","worker_lock"})jdbc.execute("DROP TABLE IF EXISTS "+table);var base=new GenerationJdbcStoreMySqlTest();base.source=source;base.jdbc=jdbc;base.schema();generation=base;setup(true);}
 };}
}
