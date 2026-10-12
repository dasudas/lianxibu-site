package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ReportInputWriterAdmissionMySqlTest extends ReportInputWriterAdmissionTest { @Override boolean mysql(){return true;} }
