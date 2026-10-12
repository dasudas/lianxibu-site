package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ReportNativeGenerationIntegrationMySqlTest extends ReportNativeGenerationIntegrationTest {@Override boolean mysql(){return true;}}
