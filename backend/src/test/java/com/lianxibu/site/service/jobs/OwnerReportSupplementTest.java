package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.IdentityService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Fictional owner endpoint consumers; complete real native report INPUT is supplied by the preparation fixture. */
class OwnerReportSupplementTest {
 boolean mysql(){return false;}
 ReportSupplementPreparationTest h; OwnerReportSupplementService service;
 void setup(boolean body)throws Exception{
  h=new ReportSupplementPreparationTest(){@Override boolean mysql(){return OwnerReportSupplementTest.this.mysql();}};
  h.setup(body);compose();
 }
 void compose(){var f=h.h.h.h.h.f.data.nativeData().fixture().f();
  service=new OwnerReportSupplementService(f.tx,new ContentReadService(new JdbcContentReads(f.source),f.tx,ContentReadService.denyUnverifiedReferences()),new JdbcLatestReportIntent(f.source),h.actions,h.service);
 }
 ContentService.ContentAction action(long revision){return new ContentService.ContentAction(revision,"supplement_generate",h.command.reason(),null);}
 ContentService.GenerationIntentWrite intent(){var day=h.command.scope().day();var zone=ZoneId.of("Asia/Shanghai");
  return new ContentService.GenerationIntentWrite("daily",day.atStartOfDay(zone).toInstant(),day.plusDays(1).atStartOfDay(zone).toInstant(),"Asia/Shanghai","supplement",0L,h.command.reason());}
 ContentService.ActionResult act(String key){return ReportOriginalCompletionTest.utc(()->service.action(h.owner,"report",h.id,action(1),key,"fictional-csrf","https://fictional.invalid"));}
 ContentService.Job gen(String key){return ReportOriginalCompletionTest.utc(()->service.generationIntent(h.owner,intent(),key,"fictional-csrf","https://fictional.invalid"));}
 @Test void ExistingContentActionCommitsNewIntentAndOriginalResponseSurvivesLaterContentAndSlotChanges()throws Exception{
  setup(true);var f=h.h.h.h.h.f.data.nativeData().fixture().f();String body=f.jdbc.queryForObject("SELECT body_json FROM contents WHERE id=?",String.class,h.id);
  var result=act("fictional-owner-content");assertEquals(2L,result.generation());assertEquals(1,result.resultRevision());assertEquals(h.id,result.resultResourceRef().id());assertEquals(2,result.affected().size());
  assertEquals(body,f.jdbc.queryForObject("SELECT body_json FROM contents WHERE id=?",String.class,h.id));assertEquals(1,h.builds);assertEquals(1,h.h.reportSends);
  f.jdbc.update("UPDATE contents SET revision=revision+1,state='blocked' WHERE id=?",h.id);f.jdbc.update("UPDATE worker_lock SET slot_revision=slot_revision+1");h.allowed=false;h.clock=mock(Clock.class);h.compose();compose();
  assertEquals(result,service.action(h.owner,"report",h.id,action(1),"fictional-owner-content","fictional-csrf","https://fictional.invalid"));verifyNoInteractions(h.clock);
  var changed=new ContentService.ContentAction(1,"supplement_generate","Fictional changed reason",null);
  var failure=assertThrows(IdentityService.Rejected.class,()->service.action(h.owner,"report",h.id,changed,"fictional-owner-content","fictional-csrf","https://fictional.invalid"));assertEquals(IdentityService.Refusal.IDENTITY_CONFLICT,failure.refusal());
 }
 @Test void AbsentTargetGenerationEndpointCreatesWholeNewIntentAndReplaysBeforeCurrentMetadata()throws Exception{
  setup(false);var first=gen("fictional-owner-absent");assertEquals("generation_intent",first.recordKind());assertEquals(h.id,first.contentId());assertEquals(0,first.callCount());assertTrue(first.attempts().isEmpty());
  var f=h.h.h.h.h.f.data.nativeData().fixture().f();assertEquals(0L,f.jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE id=?",Long.class,h.id));h.allowed=false;h.clock=mock(Clock.class);h.compose();compose();assertEquals(first,gen("fictional-owner-absent"));verifyNoInteractions(h.clock);
  var old=intent();var other=new ContentService.GenerationIntentWrite(old.kind(),old.periodStart(),old.periodEnd(),old.timezone(),old.mode(),1L,old.reason());var failure=assertThrows(IdentityService.Rejected.class,()->service.generationIntent(h.owner,other,"fictional-owner-absent","fictional-csrf","https://fictional.invalid"));assertEquals(IdentityService.Refusal.IDENTITY_CONFLICT,failure.refusal());
 }
 @Test void StaleContentRevisionAndUnchangedBusinessInputHaveExactConflictAndNoNewRoot()throws Exception{
  setup(true);var failure=assertThrows(IdentityService.Rejected.class,()->service.action(h.owner,"report",h.id,action(2),"fictional-owner-stale","fictional-csrf","https://fictional.invalid"));assertEquals(IdentityService.Refusal.REVISION_CONFLICT,failure.refusal());assertEquals(0,h.builds);
  h.preferences=new FrozenGenerationSettings(8,List.of(),List.of());failure=assertThrows(IdentityService.Rejected.class,()->act("fictional-owner-unchanged"));assertEquals(IdentityService.Refusal.REVISION_CONFLICT,failure.refusal());assertEquals("input_unchanged",failure.code());
  assertNull(new JdbcJobRecords(h.h.h.h.h.f.data.nativeData().fixture().f().source).byId(h.nextRoot()));assertEquals(1,h.h.reportSends);
 }
 @Test void CorruptOriginalReceiptCannotBorrowCurrentBodyOrCreateAnotherGeneration()throws Exception{
  setup(true);act("fictional-owner-corrupt");var f=h.h.h.h.h.f.data.nativeData().fixture().f();String operation=JdbcReportSupplementActions.operation(JdbcReportSupplementActions.action("fictional-owner-corrupt"));
  f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],operation);var failure=assertThrows(IdentityService.Rejected.class,()->act("fictional-owner-corrupt"));assertEquals(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,failure.refusal());assertEquals(1,h.builds);assertEquals(1,h.h.reportSends);
 }
 @Test void NaturalPeriodAndProtectedHeadersAreCheckedBeforeAnyNewPreparation()throws Exception{
  setup(false);var old=intent();var invalid=new ContentService.GenerationIntentWrite("daily",old.periodStart().plusSeconds(1),old.periodEnd(),old.timezone(),old.mode(),0L,old.reason());
  assertThrows(IllegalArgumentException.class,()->service.generationIntent(h.owner,invalid,"fictional-owner-period","fictional-csrf","https://fictional.invalid"));assertThrows(IllegalArgumentException.class,()->service.generationIntent(h.owner,old,"fictional-owner-header","","https://fictional.invalid"));
  var initial=new ContentService.GenerationIntentWrite(old.kind(),old.periodStart(),old.periodEnd(),old.timezone(),"initial",null,old.reason());var failure=assertThrows(IdentityService.Rejected.class,()->service.generationIntent(h.owner,initial,"fictional-owner-initial","fictional-csrf","https://fictional.invalid"));assertEquals(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,failure.refusal());assertEquals(0,h.builds);
 }
 @Test void ColdCompositionAndNonOwnerRefusalTouchNoProvider(){
  var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);var contents=mock(ContentReadService.class);var latest=mock(JdbcLatestReportIntent.class);var ledger=mock(JdbcReportSupplementActions.class);var preparation=mock(ReportSupplementPreparationService.class);
  var cold=new OwnerReportSupplementService(tx,contents,latest,ledger,preparation);var failure=assertThrows(IdentityService.Rejected.class,()->cold.action(new IdentityService.Actor("fictional-reader","reader"),null,null,null,null,null,null));assertEquals(IdentityService.Refusal.FORBIDDEN,failure.refusal());
  failure=assertThrows(IdentityService.Rejected.class,()->cold.generationIntent(null,null,null,null,null));assertEquals(IdentityService.Refusal.UNAUTHENTICATED,failure.refusal());verifyNoInteractions(tx,contents,latest,ledger,preparation);
 }
}
