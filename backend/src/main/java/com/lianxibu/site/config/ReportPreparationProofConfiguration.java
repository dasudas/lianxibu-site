package com.lianxibu.site.config;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenContentVersions;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.articles.*;

import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** Cold formal report preparation providers. Background orchestration is a separate opt-in runtime. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class ReportPreparationProofConfiguration {
 @Bean public ReportPreparedInputService reportPreparedInputs(DataSource source,PlatformTransactionManager tx){return new ReportPreparedInputService(source,tx);}
 @Bean public ReportInputWriterAdmissionService reportInputWriterAdmission(DataSource source,PlatformTransactionManager tx,ReportInputCaptureService inputs){return new ReportInputWriterAdmissionService(source,tx,inputs);}
 @Bean public ReportPreparationService reportPreparation(DataSource source,PlatformTransactionManager tx,ReportPreparedInputService originals,ReportInputWriterAdmissionService admission,SettingsUpdateService settings,OperationalInputGate gate){
  var members=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source));var articles=new ArticlePublicEvidenceService(new JdbcArticlePublicEvidence(source),members);
  var metadata=new ReportPublicMetadataEnricher(articles,members,new FrozenContentVersionService(new JdbcFrozenContentVersions(source)));
  return new ReportPreparationService(source,originals,admission,new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),()->{var frozen=settings.freezeForNewInput();return new FrozenGenerationSettings(frozen.settingsRevision(),frozen.followKeywords(),frozen.blockedKeywords());},metadata,new GenerationRequestBuilder(),Clock.systemUTC(),gate::preparationAllowed);
 }
}
