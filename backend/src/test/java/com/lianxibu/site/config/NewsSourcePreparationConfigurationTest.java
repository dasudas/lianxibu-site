package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NewsSourcePreparationConfigurationTest {
    static final String[] FLAGS={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.execution.preparation.enabled"};
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(NewsSourcePreparationConfiguration.class)
            .withBean(DataSource.class,()->mock(DataSource.class)).withBean(SettingsUpdateService.class,()->mock(SettingsUpdateService.class))
            .withBean(org.springframework.transaction.PlatformTransactionManager.class,()->mock(org.springframework.transaction.PlatformTransactionManager.class))
            .withBean(OperationalInputGate.class,()->mock(OperationalInputGate.class));
    @Test void disabledByDefaultAndEachExplicitPrerequisiteRequired(){
        context.run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("newsSourcePlanPreparation"));assertFalse(c.containsBean("sourceStagePreparation"));assertFalse(c.containsBean("newsValueSourcePlanPreparation"));assertFalse(c.containsBean("sourceDedupStageProof"));assertFalse(c.containsBean("sourceDedupStageAvailability"));});
        for(String absent:FLAGS)context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new))
                .run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("newsSourcePlanPreparation"));assertFalse(c.containsBean("sourceStagePreparation"));assertFalse(c.containsBean("newsValueSourcePlanPreparation"));assertFalse(c.containsBean("sourceDedupStageProof"));assertFalse(c.containsBean("sourceDedupStageAvailability"));});
    }
    @Test void actualProvidersAssembleWithoutStartingQueriesOrCallingSettings(){
        context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->{
            assertNull(c.getStartupFailure());assertNotNull(c.getBean(NewsSourcePlanPreparationService.class));assertNotNull(c.getBean(SourceStagePreparationService.class));
            assertNotNull(c.getBean(NewsValueSourcePlanPreparationService.class));assertNotNull(c.getBean(SourceDedupStageProofService.class));assertNotNull(c.getBean(SourceDedupStageAvailabilityService.class));
            verifyNoInteractions(c.getBean(DataSource.class),c.getBean(SettingsUpdateService.class),c.getBean(OperationalInputGate.class),c.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            assertThrows(RuntimeException.class,()->c.getBean(NewsSourcePlanPreparationService.class).prepare("fictional-intent","fictional-run"));
            verifyNoInteractions(c.getBean(DataSource.class));
        });
    }
    @Test void completeValueProofAndObservationHaveTheSameExplicitFlagsAndZeroStartupInteractions(){
        context.run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("sourceValueStageProof"));assertFalse(c.containsBean("sourceValueStageAvailability"));});
        for(String absent:FLAGS)context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("sourceValueStageProof"));assertFalse(c.containsBean("sourceValueStageAvailability"));});
        context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(SourceValueStageProofService.class));assertNotNull(c.getBean(SourceValueStageAvailabilityService.class));verifyNoInteractions(c.getBean(DataSource.class),c.getBean(SettingsUpdateService.class),c.getBean(OperationalInputGate.class),c.getBean(org.springframework.transaction.PlatformTransactionManager.class));});
    }
    @Test void finalSourceProvidersHaveAllSevenPrerequisitesAndZeroStartupInteractions(){
        var names=java.util.List.of("sourceValueFinalProof","sourceValueFinalReceipts","sourceRunAnalysis","sourceValueFinalArchive","sourceValueFinalization","sourceValueFinalAvailability");
        context.run(c->{assertNull(c.getStartupFailure());names.forEach(n->assertFalse(c.containsBean(n)));});
        for(String absent:FLAGS)context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());names.forEach(n->assertFalse(c.containsBean(n)));});
        context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());names.forEach(n->assertNotNull(c.getBean(n)));verifyNoInteractions(c.getBean(DataSource.class),c.getBean(SettingsUpdateService.class),c.getBean(OperationalInputGate.class),c.getBean(org.springframework.transaction.PlatformTransactionManager.class));});
    }
    @Test void durableIntentHandoffProvidersHaveAllSevenPrerequisitesAndZeroStartupInteractions(){
        var names=java.util.List.of("sourceValueIntentReceipts","sourceValueIntents");
        context.run(c->{assertNull(c.getStartupFailure());names.forEach(n->assertFalse(c.containsBean(n)));});
        for(String absent:FLAGS)context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());names.forEach(n->assertFalse(c.containsBean(n)));});
        context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(SourceValueIntentReceiptService.class));assertNotNull(c.getBean(SourceValueIntentService.class));verifyNoInteractions(c.getBean(DataSource.class),c.getBean(SettingsUpdateService.class),c.getBean(OperationalInputGate.class),c.getBean(org.springframework.transaction.PlatformTransactionManager.class));});
    }
    @Test void planCoordinatorHasAllSevenPrerequisitesAndZeroStartupInteractions(){
        context.run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("sourcePlanPreparationCoordinator"));});
        for(String absent:FLAGS)context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("sourcePlanPreparationCoordinator"));});
        context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(SourcePlanPreparationCoordinator.class));verifyNoInteractions(c.getBean(DataSource.class),c.getBean(SettingsUpdateService.class),c.getBean(OperationalInputGate.class),c.getBean(org.springframework.transaction.PlatformTransactionManager.class));});
    }
    @Test void parentStepUsesAllSevenPrerequisitesAndHasNoStartupInteractions(){
        context.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceAnalysisParentStepService.class).isEmpty());});
        for(String absent:FLAGS)context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceAnalysisParentStepService.class).isEmpty());});
        context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(SourceAnalysisParentStepService.class));verifyNoInteractions(c.getBean(DataSource.class),c.getBean(SettingsUpdateService.class),c.getBean(OperationalInputGate.class),c.getBean(org.springframework.transaction.PlatformTransactionManager.class));});
    }
}
