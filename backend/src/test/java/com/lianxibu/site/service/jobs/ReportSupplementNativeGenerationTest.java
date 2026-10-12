package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import com.lianxibu.site.config.ReportPeriodInputProofConfiguration;
import com.lianxibu.site.config.ResultApplicationFactory;
import com.lianxibu.site.config.SourceAnalysisProofGraph;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real native supplementary report preparation, authorization, application and original completion.
 * Source/settings genesis is fictional and source NEWS/count is empty. Actual model transport is a fake sender. */
class ReportSupplementNativeGenerationTest {
 boolean mysql(){return false;}
 ReportSupplementPreparationTest m;ReportSupplementPreparationService.Result prepared;
 WorkerAuthorizationService worker;WorkerAuthorizationService.Permit permit;WorkerAuthorizationService.Identity identity;
 ResultApplicationFactory factory;AttemptOutcomeService.Application application;String raw;Clock clock;int sends;
 void setup(boolean body)throws Exception{
  m=new ReportSupplementPreparationTest(){@Override boolean mysql(){return ReportSupplementNativeGenerationTest.this.mysql();}};
  m.setup(body);prepared=m.prepare("fictional-native-supplement");clock=Clock.fixed(m.clock.instant().plusSeconds(1),ZoneOffset.UTC);
  var base=m.h.h.h.h.f.data.nativeData().fixture().f();
  worker=new WorkerAuthorizationService(base.writes,new WorkerJdbcStore(new JdbcWorkerState(base.source),new JdbcJobRecords(base.source),"fictional-native-supplement-worker",()->false,m.h.h.readiness,clock,base.tx),clock,()->true);
  var graph=new SourceAnalysisProofGraph(base.source,base.tx,clock,()->false);var catalogs=new LinkedHashMap<String,CatalogDayAnalysisResultPermitService>();
  for(String category:List.of("agent","skill","workflow","model"))catalogs.put(category,mock(CatalogDayAnalysisResultPermitService.class));
  factory=new ResultApplicationFactory(base.source,base.tx,clock,graph.dependencies(),graph.dedupPermits(),catalogs,new JdbcEntityHeatSnapshots(base.source),m.h.h.h.permits);
 }
 void send(){ReportOriginalCompletionTest.utc(()->{var claim=worker.claim(new SharedWriteExecutor.ActionContext("fictional-supplement-claim","fictional-supplement-claim",prepared.prepared().input().inputHash()));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());permit=claim.permit();identity=permit.identity();assertEquals(prepared.accepted().rootId(),identity.rootId());
  var base=m.h.h.h.h.f.data.nativeData().fixture().f();var complete=ReportPreparationInputProfile.complete(CanonicalJson.parse(prepared.prepared().input().snapshotJson()));raw=m.h.raw(complete.command().applicationContext().input(identity.rootId(),identity.attemptId(),identity.inputHash(),new JdbcJobRecords(base.source).byId(identity.rootId()).revision()));
  var sent=worker.dispatch(permit,request->{assertEquals(identity.frozenRequestJson(),request);sends++;return raw;});assertEquals(WorkerAuthorizationService.State.SENT,sent.state());application=factory.forAttempt(identity);return true;});}
 ResultApplicationService.Result apply(){return ReportOriginalCompletionTest.utc(()->application.service().applyAutomatic(application.input(),raw,new SharedWriteExecutor.ActionContext("fictional-native-supplement-result",identity.rootId(),identity.inputHash())));}
 JdbcReportCompletionReceipts.Ready ready(String root){var base=m.h.h.h.h.f.data.nativeData().fixture().f();return m.h.h.h.h.f.rr(()->new JdbcReportCompletionReceipts(base.source).read(root));}
 @Test void SecondGenerationActuallySendsAndReplacesBodyWhileKeepingOriginalDailyAndSameAction()throws Exception{
  setup(true);var base=m.h.h.h.h.f.data.nativeData().fixture().f();String oldRoot=m.h.identity.rootId();var oldReady=ready(oldRoot);assertNotNull(oldReady);
  var availability=new VersionAvailabilityService(new FrozenContentVersionService(new JdbcFrozenContentVersions(base.source)),new JdbcVersionAvailability(base.source),base.writes,base.tx,clock);
  var oldFirst=ReportOriginalCompletionTest.utc(()->availability.capture(oldReady.version()));var completion=new ReportOriginalCompletionService(base.source,base.tx);var old=completion.find(oldRoot,oldFirst);assertNotNull(old);
  send();assertEquals(ResultApplicationService.Status.APPLIED,apply().status());assertEquals(2L,base.jdbc.queryForObject("SELECT revision FROM contents WHERE id=?",Long.class,m.id));assertEquals(2L,base.jdbc.queryForObject("SELECT body_revision FROM contents WHERE id=?",Long.class,m.id));assertEquals(identity.rootId(),base.jdbc.queryForObject("SELECT input_job_id FROM contents WHERE id=?",String.class,m.id));
  assertEquals(old,completion.find(oldRoot,clock.instant().plusSeconds(1)));var secondReady=ready(identity.rootId());assertNotNull(secondReady);
  var later=new VersionAvailabilityService(new FrozenContentVersionService(new JdbcFrozenContentVersions(base.source)),new JdbcVersionAvailability(base.source),base.writes,base.tx,Clock.fixed(clock.instant().plusSeconds(1),ZoneOffset.UTC));var first=ReportOriginalCompletionTest.utc(()->later.capture(secondReady.version()));var second=completion.find(identity.rootId(),first);assertNotNull(second);assertEquals(2,second.header().generation());
  var day=prepared.prepared().scope().day();var last=day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));var cutoff=last.plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();var period=ReportPeriodInputProofConfiguration.assemble(base.source,base.tx,clock,completion).capture(Stage.WEEKLY_REPORT,last,cutoff);assertEquals(1,period.candidates().size());assertEquals(second.bodyJson(),period.candidates().getFirst().evidence().bodyJson());
  assertEquals(ResultApplicationService.Status.APPLIED,apply().status());assertEquals(ResultApplicationService.Status.APPLIED,m.h.apply().status());assertEquals(identity.rootId(),base.jdbc.queryForObject("SELECT input_job_id FROM contents WHERE id=?",String.class,m.id));assertEquals(1,sends);assertEquals(1,m.h.reportSends);assertEquals(1,new JdbcJobRecords(base.source).byId(identity.rootId()).callCount());assertEquals("free",new JdbcWorkerState(base.source).slot().state());
 }
 @Test void FirstGenerationLateResultIsSupersededAndRetainedBeforeOnlySecondCreatesBody()throws Exception{
  setup(false);var base=m.h.h.h.h.f.data.nativeData().fixture().f();assertEquals("occupied",new JdbcWorkerState(base.source).slot().state());assertEquals(ResultApplicationService.Status.SUPERSEDED,m.h.apply().status());assertNotNull(new JdbcResultState(base.source).root(m.h.identity.rootId()).resultHash());assertEquals(0L,base.jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE id=?",Long.class,m.id));assertNull(ready(m.h.identity.rootId()));assertEquals("free",new JdbcWorkerState(base.source).slot().state());
  send();assertEquals(ResultApplicationService.Status.APPLIED,apply().status());assertEquals(1L,base.jdbc.queryForObject("SELECT revision FROM contents WHERE id=?",Long.class,m.id));assertEquals(identity.rootId(),base.jdbc.queryForObject("SELECT input_job_id FROM contents WHERE id=?",String.class,m.id));assertNotNull(ready(identity.rootId()));assertEquals(1,sends);assertEquals(1,m.h.reportSends);
 }
 @Test void ContentRevisionChangeAfterActualSupplementSendRetainsResultWithoutOverwritingBody()throws Exception{
  setup(true);send();var base=m.h.h.h.h.f.data.nativeData().fixture().f();String oldBody=base.jdbc.queryForObject("SELECT body_json FROM contents WHERE id=?",String.class,m.id);base.jdbc.update("UPDATE contents SET revision=revision+1 WHERE id=?",m.id);
  assertEquals(ResultApplicationService.Status.CONTENT_REVISION_CONFLICT,apply().status());assertEquals(oldBody,base.jdbc.queryForObject("SELECT body_json FROM contents WHERE id=?",String.class,m.id));assertEquals(m.h.identity.rootId(),base.jdbc.queryForObject("SELECT input_job_id FROM contents WHERE id=?",String.class,m.id));assertNotNull(new JdbcResultState(base.source).root(identity.rootId()).resultHash());assertNull(ready(identity.rootId()));assertEquals(1,sends);
 }
 @Test void SupplementReadyFailureRollsBackBodyAndPreservesRawResultAndOldReady()throws Exception{
  setup(true);send();var base=m.h.h.h.h.f.data.nativeData().fixture().f();String oldBody=base.jdbc.queryForObject("SELECT body_json FROM contents WHERE id=?",String.class,m.id);
  String nextReady=JdbcFrozenInputs.stableId("report-ready","report-ready:"+identity.rootId());base.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_supplement_ready_failure CHECK(target_type IS NULL OR target_type<>'report_result_ready' OR id<>'"+nextReady+"')");assertThrows(RuntimeException.class,this::apply);assertEquals(oldBody,base.jdbc.queryForObject("SELECT body_json FROM contents WHERE id=?",String.class,m.id));assertEquals(1L,base.jdbc.queryForObject("SELECT revision FROM contents WHERE id=?",Long.class,m.id));assertNotNull(new JdbcResultState(base.source).root(identity.rootId()).resultHash());assertNull(ready(identity.rootId()));assertNotNull(ready(m.h.identity.rootId()));assertEquals(1,sends);
 }
}
