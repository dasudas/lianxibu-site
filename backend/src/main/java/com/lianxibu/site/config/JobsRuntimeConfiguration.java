package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsJdbcStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Unique owner Jobs API assembly, with no startup I/O or execution loop. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.execution.enabled"},havingValue="true")
@org.springframework.context.annotation.Import(ResultApplicationConfiguration.class)
public class JobsRuntimeConfiguration {
    @Bean OwnerJobsReadService ownerJobsReads(DataSource source,PlatformTransactionManager tx){
        var views=new JdbcOwnerJobViews(source);return new OwnerJobsReadService(views,new OwnerJobInputMetadataService(views,new JdbcFrozenInputs(source)),tx);
    }
    @Bean OwnerAdHocIntentCancelService ownerAdHocIntentCancellation(DataSource source,PlatformTransactionManager tx){return new OwnerAdHocIntentCancelService(source,tx,new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),Clock.systemUTC());}
    @Bean OwnerJobCancelService ownerJobCancellation(DataSource source,PlatformTransactionManager tx,OwnerAdHocIntentCancelService intents){
        return new OwnerJobCancelService(new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),new JdbcOwnerJobCancellation(source),Clock.systemUTC(),intents);
    }
    @Bean OwnerJobCloseUnresolvedService ownerJobClosure(DataSource source,PlatformTransactionManager tx){
        var state=new JdbcWorkerState(source);
        var store=new UnknownCallJdbcStore(state,new JdbcWorkerRecovery(source,state),new JdbcJobRecords(source),"owner-job-close",ignored->false);
        return new OwnerJobCloseUnresolvedService(new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),new JdbcOwnerJobClosureGuard(source),store,Clock.tick(Clock.systemUTC(),java.time.Duration.ofNanos(1000)));
    }
    @Bean @ConditionalOnProperty(name={"site.articles.enabled","site.sources.enabled"},havingValue="true")
    OwnerJobRecoverService ownerJobRecovery(DataSource source,PlatformTransactionManager tx,DispatchReadinessService readiness){
        return new OwnerJobRecoverService(new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),new JdbcOwnerJobRecovery(source),readiness,tx,Clock.systemUTC());
    }
    @Bean OwnerJobDiscardResultService ownerJobDiscard(DataSource source,PlatformTransactionManager tx){
        return new OwnerJobDiscardResultService(new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),new JdbcOwnerResultDiscard(source),new JdbcFrozenInputs(source),Clock.systemUTC());
    }
    @Bean OwnerJobApplyResultService ownerJobApplication(DataSource source,PlatformTransactionManager tx,ObjectProvider<ResultApplicationFactory> actual){
        Clock clock=Clock.tick(Clock.systemUTC(),java.time.Duration.ofNanos(1000));var factory=actual.getIfAvailable();if(factory==null)factory=new ResultApplicationFactory(source,tx,clock);
        return new OwnerJobApplyResultService(new JdbcOwnerRetainedResultReads(source),tx,factory::forManual);
    }
    @Bean OwnerJobResolveExternalService ownerJobResolution(DataSource source,PlatformTransactionManager tx){return new OwnerJobResolveExternalService(source,tx,new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),Clock.tick(Clock.systemUTC(),java.time.Duration.ofNanos(1000)));}
    @Bean OwnerJobsService.Commands ownerJobCommands(OwnerJobCancelService cancellation,OwnerJobCloseUnresolvedService closure,ObjectProvider<OwnerJobRecoverService> recovery,OwnerJobDiscardResultService discard,OwnerJobApplyResultService application,OwnerJobResolveExternalService resolution,ObjectProvider<OwnerAdHocCreateService> creation,org.springframework.core.env.Environment flags){var actual=creation.getIfAvailable();if(flags.getProperty("site.execution.ad-hoc.enabled",Boolean.class,false)&&actual==null)throw new IllegalStateException("original temporary creation provider required");return new OwnerJobCommandsService(cancellation,closure,recovery.getIfAvailable(),discard,application,resolution,actual);}
    @Bean JobsService jobsService(OwnerJobsReadService reads,ObjectProvider<OwnerJobsService.Commands> commands,DataSource source,PlatformTransactionManager tx,SettingsJdbcStore settings){
        Clock clock=Clock.systemUTC();var store=new OperationalJdbcStore(new JdbcOperationalSummaries(source),new JdbcJobRecords(source),clock);
        var monitor=new OperationalMonitoringService(store,()->{var configuration=settings.operationalConfiguration();return configuration.ready()?new OperationalMonitoringService.Configuration(configuration.revision(),configuration.rules()):null;},new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),clock);
        return new OperationalJobsService(store,monitor,new OwnerJobsService(reads,commands.getIfAvailable()),tx,clock);
    }
}
