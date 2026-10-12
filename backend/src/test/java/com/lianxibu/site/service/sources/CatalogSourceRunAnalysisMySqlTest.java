package com.lianxibu.site.service.sources;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CatalogSourceRunAnalysisMySqlTest extends CatalogSourceRunAnalysisServiceTest { @Override javax.sql.DataSource source(){return SourceRunReadServiceMySqlTest.mysql();} }
