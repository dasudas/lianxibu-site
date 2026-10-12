package com.lianxibu.site.service.jobs;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
/** One initial report scope per tick. Locators schedule work; native preparation owns all input authority. */
public final class ReportPreparationCycle {
 public enum Outcome { IDLE,BUSY,GATED,WAITING,PREPARED,RECOVERY_REQUIRED }
 private final JdbcReportSchedulePages pages;private final ReportPreparationService preparation;
 private final ReportNoUpdateProofService noUpdates;private final Clock clock;private final BooleanSupplier allowed;
 private final TransactionTemplate reads;private final AtomicBoolean active=new AtomicBoolean();
 private String cursor;private JdbcReportSchedulePages.Locator pending;private boolean more;private int next;
 public ReportPreparationCycle(JdbcReportSchedulePages pages,ReportPreparationService preparation,ReportNoUpdateProofService noUpdates,PlatformTransactionManager tx,Clock clock,BooleanSupplier allowed){
  this.pages=Objects.requireNonNull(pages);this.preparation=Objects.requireNonNull(preparation);this.noUpdates=Objects.requireNonNull(noUpdates);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);
  reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);reads.setTimeout(1);
 }
 public Outcome run(BooleanSupplier running){
  Objects.requireNonNull(running);if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
  if(!active.compareAndSet(false,true))return Outcome.BUSY;
  boolean reached=false;
  try{
   if(!running.getAsBoolean()||Thread.currentThread().isInterrupted())return Outcome.IDLE;
   if(!allowed.getAsBoolean())return Outcome.GATED;
   if(pending==null){
    var page=Objects.requireNonNull(reads.execute(s->pages.read(cursor,1)));
    if(page.rows().size()>1||page.hasMore()&&page.rows().size()!=1)throw invalid();
    if(page.rows().isEmpty()){cursor=null;return Outcome.IDLE;}
    var locator=page.rows().getFirst();scopes(locator);
    if(cursor!=null&&locator.id().compareTo(cursor)<=0||!locator.id().equals(page.nextCursor()))throw invalid();
    pending=locator;more=page.hasMore();next=0;
   }
   var scope=scopes(pending).get(next);
   if(!running.getAsBoolean()||Thread.currentThread().isInterrupted())return Outcome.IDLE;
   Instant cutoff=scope.day().plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
   if(Objects.requireNonNull(clock.instant()).isBefore(cutoff)){advance();return Outcome.WAITING;}
   if(!allowed.getAsBoolean())return Outcome.GATED;
   reached=true;
   var original=Objects.requireNonNull(preparation.prepare(scope));
   if(!scope.equals(original.scope()))throw invalid();
   if(scope.stage()==GenerationFlowService.Stage.DAILY_REPORT){
    // If shutdown begins after commit, the next tick retries observation through the original receipt.
    if(!running.getAsBoolean()||Thread.currentThread().isInterrupted())return Outcome.IDLE;
    noUpdates.observe(scope);
   }
   advance();return Outcome.PREPARED;
  }catch(RuntimeException unavailable){if(reached)advance();else{pending=null;cursor=null;next=0;}return Outcome.RECOVERY_REQUIRED;}
  finally{active.set(false);}
 }
 private void advance(){if(pending==null)return;if(++next>=scopes(pending).size()){cursor=more?pending.id():null;pending=null;next=0;}}
 private static List<ReportInputCaptureService.Scope> scopes(JdbcReportSchedulePages.Locator locator){
  Objects.requireNonNull(locator);var day=Objects.requireNonNull(locator.day());
  if("source_day_roster".equals(locator.type())){
   if(!JdbcSourceDayRosters.owner(day).equals(locator.id())||!("source-day-roster:"+day).equals(locator.logicalKey()))throw invalid();
   return List.of(scope(GenerationFlowService.Stage.DAILY_REPORT,day),scope(GenerationFlowService.Stage.WEEKLY_REPORT,day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))),scope(GenerationFlowService.Stage.MONTHLY_REPORT,day.with(TemporalAdjusters.lastDayOfMonth())));
  }
  var stage=switch(locator.type()){case "daily_report"->GenerationFlowService.Stage.DAILY_REPORT;case "weekly_report"->{if(day.getDayOfWeek()!=DayOfWeek.SUNDAY)throw invalid();yield GenerationFlowService.Stage.WEEKLY_REPORT;}case "monthly_report"->{if(day.getDayOfMonth()!=day.lengthOfMonth())throw invalid();yield GenerationFlowService.Stage.MONTHLY_REPORT;}default->throw invalid();};
  var result=scope(stage,day);if(!result.logicalKey().equals(locator.logicalKey())||!JdbcFrozenInputs.stableId("intent",result.logicalKey()+":1").equals(locator.id()))throw invalid();return List.of(result);
 }
 private static ReportInputCaptureService.Scope scope(GenerationFlowService.Stage stage,LocalDate day){return new ReportInputCaptureService.Scope("report:"+stage.name().toLowerCase(Locale.ROOT).replace("_report","")+":"+day,1,stage,day);}
 private static IllegalStateException invalid(){return new IllegalStateException("original report preparation cycle unavailable");}
 @Override public String toString(){return "ReportPreparationCycle[redacted]";}
}
