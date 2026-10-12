package com.lianxibu.site.service.jobs;

class NewsSourcePlanCommitMySqlTest extends NewsSourcePlanCommitTest {
    @Override NewsSourcePlanPreparationTest fixture(){return new NewsSourcePlanPreparationMySqlTest();}
}
