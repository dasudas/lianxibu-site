package com.lianxibu.site.config;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Cold owner report commands over the same full native proof providers as scheduled preparation. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class OwnerReportCommandConfiguration {
 @Bean public OwnerReportOperations ownerReportOperations(DataSource source,PlatformTransactionManager tx,ReportPreparedInputService originals,ReportInputWriterAdmissionService admission,SettingsUpdateService settings,OperationalInputGate gate){
  var clock=Clock.systemUTC();var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);var intents=new JdbcLatestReportIntent(source);var actions=new JdbcReportSupplementActions(source);
  var content=new ContentReadService(new JdbcContentReads(source),tx,ContentReadService.denyUnverifiedReferences());
  var baseline=new ReportSupplementBaselineService(source,tx,intents,originals,new ReportSupplementContentBaselineService(source,tx));
  var members=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source));var articles=new ArticlePublicEvidenceService(new JdbcArticlePublicEvidence(source),members);
  var metadata=new ReportPublicMetadataEnricher(articles,members,new FrozenContentVersionService(new JdbcFrozenContentVersions(source)));
  var preparation=new ReportSupplementPreparationService(source,tx,originals,baseline,admission,actions,writes,()->{var value=settings.freezeForNewInput();return new FrozenGenerationSettings(value.settingsRevision(),value.followKeywords(),value.blockedKeywords());},metadata,new GenerationRequestBuilder(),clock,gate::preparationAllowed);
  var initial=new OwnerReportInitialService(source,new JdbcInitialReportIntent(source),new JdbcReportInitialActions(source),originals,writes,clock,gate::preparationAllowed);
  return new OwnerReportOperations(initial,new OwnerReportSupplementService(tx,content,intents,actions,preparation));
 }
}
