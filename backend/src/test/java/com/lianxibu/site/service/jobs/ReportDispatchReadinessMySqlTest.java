package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ReportDispatchReadinessMySqlTest extends ReportDispatchReadinessTest {@Override boolean mysql(){return true;}}
