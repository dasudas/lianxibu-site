package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CatalogDayAnalysisInputPermitMySqlTest extends CatalogDayAnalysisInputPermitTest {@Override boolean mysql(){return true;}}
