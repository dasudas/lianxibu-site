package com.lianxibu.site.service.jobs;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NewsSourcePreparationAssemblyMySqlTest extends NewsSourcePreparationAssemblyTest {
    @Override NewsSourcePlanPreparationTest fixture(){return new NewsSourcePlanPreparationMySqlTest();}
}
