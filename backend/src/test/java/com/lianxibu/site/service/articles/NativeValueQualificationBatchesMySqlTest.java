package com.lianxibu.site.service.articles;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NativeValueQualificationBatchesMySqlTest extends NativeValueQualificationBatchesTest {
 @Override DataSource source(){return new NewsValueEligibilityMySqlTest().source();}
 @Override String jsonType(){return "JSON";}
}
