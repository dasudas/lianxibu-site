package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CatalogDayTwoSourceIntegrationMySqlTest extends CatalogDayTwoSourceIntegrationTest {@Override boolean mysql(){return true;}}
