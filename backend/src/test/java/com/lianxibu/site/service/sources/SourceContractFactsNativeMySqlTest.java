package com.lianxibu.site.service.sources;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceContractFactsNativeMySqlTest extends SourceContractFactsNativeTest {@Override javax.sql.DataSource source(){return SourceContractNativeMySqlFixture.mysql();}}
