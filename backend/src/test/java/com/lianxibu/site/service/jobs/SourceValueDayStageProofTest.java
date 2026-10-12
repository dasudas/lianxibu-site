package com.lianxibu.site.service.jobs;
/** Original full archives, native outcomes and observation SQL; day is a pure fictional reference. */
class SourceValueDayStageProofTest extends SourceValueStageProofTest {
    @Override SourcePartitionPlanner.Plan originalPlan(NewsValueSourcePlanBuilderTest.Fixture x){var days=new NewsValueDayPlanBuilderTest();return days.build(x,x.dependency(),days.day(x.dependency(),x.selection().cutoff(),false));}
}
