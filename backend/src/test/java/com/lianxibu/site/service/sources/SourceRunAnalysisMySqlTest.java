package com.lianxibu.site.service.sources;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceRunAnalysisMySqlTest extends SourceRunAnalysisServiceTest {
    @Override javax.sql.DataSource source(){return SourceRunReadServiceMySqlTest.mysql();}
}
