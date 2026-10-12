package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ReportPreparedReceiptMySqlTest extends ReportPreparedReceiptTest {@Override boolean mysql(){return true;}}
