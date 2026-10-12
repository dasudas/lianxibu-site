package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
/** Actual original acceptance to immutable INPUT/request, with durable commit recovery. */
class OwnerAdHocPreparationTest {
 OwnerAdHocCreationTest c;
 OwnerAdHocCreationTest fixtureSource(){return new OwnerAdHocCreationTest();}
 @BeforeEach void setup(){c=fixtureSource();c.setup();}
 OwnerAdHocPreparationService prepare(){return new OwnerAdHocPreparationService(c.f.source,c.accepted,c.f.writes,new DataSourceTransactionManager(c.f.source),c.f.clock,()->c.allowed);}
 @Test void OriginalPromptSettingsContextAndFullRequestAreFrozenBeforeAnyCall(){
  var original=c.first();var ready=prepare().prepare(original.actionKey());assertEquals("queued",ready.state());assertEquals(14,ready.priority());assertEquals(Instant.parse("2026-01-02T17:00:00Z"),ready.eligibleAt());assertEquals(0,c.f.count("record_kind='logical_task' AND call_count<>0"));assertEquals(1,c.f.count("record_kind='ai_attempt' AND state='prepared'"));
  var input=CanonicalJson.parse(ready.snapshotJson());assertEquals(original.command().prompt(),input.get("members").get(0).get("body").get("prompt").stringValue());assertEquals(JdbcFrozenInputs.canonical(original.settings().toJson()),JdbcFrozenInputs.canonical(input.get(FrozenGenerationSettings.FIELD)));assertEquals("ad_hoc_ai",input.get(FrozenApplicationContext.FIELD).get("stage").stringValue());assertTrue(input.get(FrozenApplicationContext.FIELD).get("target_content").isNull());
  assertEquals(ready.snapshotJson(),new JdbcFrozenInputs(c.f.source).read(original.command().rootId(),ready.inputHash()));assertEquals(ready.requestJson(),new GenerationRequestBuilder().build(GenerationFlowService.Stage.AD_HOC_AI,ready.snapshotJson(),ready.inputHash()));assertEquals(3,new JdbcJobRecords(c.f.source).byId(original.command().rootId()).maxAttempts());
 }
 @Test void PauseDefersNewPreparationAndCannotInvalidateTheOriginalCompletedReceipt(){
  var original=c.first();c.allowed=false;assertThrows(GenerationFlowService.CapacityGuarded.class,()->prepare().prepare(original.actionKey()));assertEquals(2,c.f.count("1=1"));c.allowed=true;var ready=prepare().prepare(original.actionKey());int count=c.f.count("1=1");c.allowed=false;assertEquals(ready,prepare().prepare(original.actionKey()));assertEquals(count,c.f.count("1=1"));assertEquals(1,c.freezes);
 }
 @Test void RestartUsesTheOriginalFrozenPreferencesAndNeverReopensAnInputBudget(){
  var original=c.first();var first=prepare().prepare(original.actionKey());assertEquals(first,prepare().prepare(original.actionKey()));assertEquals(original.json(),c.first().json());assertEquals(1,c.freezes);assertEquals(1,c.f.count("record_kind='logical_task'"));assertEquals(1,c.f.count("record_kind='ai_attempt'"));assertEquals(0,c.f.count("record_kind='logical_task' AND call_count<>0"));
 }
 @Test void CorruptOriginalAcceptanceOrNativeIntentCannotProduceAnyRoot(){
  var original=c.first();c.f.jdbc.update("UPDATE jobs SET logical_key_hash=? WHERE record_kind='generation_intent'",new byte[32]);assertThrows(IllegalStateException.class,()->prepare().prepare(original.actionKey()));assertEquals(2,c.f.count("1=1"));
 }
 @Test void AChangedCurrentIntentRevisionCannotAuthorizeTheFirstPreparation(){
  var original=c.first();c.f.jdbc.update("UPDATE jobs SET revision=1 WHERE record_kind='generation_intent'");assertEquals("revision_conflict",assertThrows(JdbcOwnerAdHocCreation.Rejected.class,()->prepare().prepare(original.actionKey())).getMessage());assertEquals(2,c.f.count("1=1"));assertEquals(original.json(),c.first().json());
 }
 @Test void CorruptPreparedInputCannotBeRebuiltFromAnOtherwiseGoodOriginalAcceptance(){
  var original=c.first();prepare().prepare(original.actionKey());int count=c.f.count("1=1");c.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE record_kind='snapshot_part' AND purpose='input'",new byte[32]);assertThrows(RuntimeException.class,()->prepare().prepare(original.actionKey()));assertEquals(count,c.f.count("1=1"));assertEquals(1,c.f.count("record_kind='ai_attempt'"));
 }
 @Test void PreparationFailureRollsBackEveryRootRowWhileKeepingTheOriginalAcceptance(){
  var original=c.first();c.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_preparation_failure CHECK(record_kind<>'ai_attempt')");assertThrows(RuntimeException.class,()->prepare().prepare(original.actionKey()));assertEquals(2,c.f.count("1=1"));assertEquals(original.json(),c.first().json());assertEquals("free",c.f.jdbc.queryForObject("SELECT slot_state FROM worker_lock WHERE id=1",String.class));
 }
 @Test void BuildingAndPreparingInsideAnOuterTransactionAreRejected(){
  var original=c.first();var tx=new TransactionTemplate(new DataSourceTransactionManager(c.f.source));assertThrows(IllegalStateException.class,()->tx.execute(s->prepare().prepare(original.actionKey())));assertThrows(IllegalStateException.class,()->tx.execute(s->new OwnerAdHocInputBuilder().build(original)));assertEquals(2,c.f.count("1=1"));
 }
 static final class Ack implements PlatformTransactionManager {
  final PlatformTransactionManager delegate;final Map<TransactionStatus,Boolean> readonly=new IdentityHashMap<>();boolean lose=true;
  Ack(PlatformTransactionManager delegate){this.delegate=delegate;}
  public TransactionStatus getTransaction(TransactionDefinition definition){var s=delegate.getTransaction(definition);readonly.put(s,definition!=null&&definition.isReadOnly());return s;}
  public void commit(TransactionStatus s){boolean read=Boolean.TRUE.equals(readonly.remove(s));delegate.commit(s);if(!read&&lose){lose=false;throw new DataAccessResourceFailureException("fictional acknowledgement lost after native commit");}}
  public void rollback(TransactionStatus s){readonly.remove(s);delegate.rollback(s);}
 }
 @Test void LostCommitAcknowledgementsRecoverNativeAcceptanceAndPreparationWithoutDuplicates(){
  var ack=new Ack(new DataSourceTransactionManager(c.f.source));var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(c.f.source),ack,()->25,n->{});var create=new OwnerAdHocCreateService(writes,c.accepted,()->new FrozenGenerationSettings(8,List.of(),List.of()),()->true,c.f.clock);
  var original=create.create(c.owner,"fictional-question","Fictional ACK question","Fictional reason","fictional-ack-create");assertFalse(ack.lose);assertEquals(2,c.f.count("1=1"));ack.lose=true;var preparer=new OwnerAdHocPreparationService(c.f.source,c.accepted,writes,ack,c.f.clock,()->true);var ready=preparer.prepare(original.actionKey());assertFalse(ack.lose);assertEquals(ready,preparer.prepare(original.actionKey()));assertEquals(1,c.f.count("record_kind='logical_task'"));assertEquals(1,c.f.count("record_kind='ai_attempt'"));assertEquals(0,c.f.count("record_kind='logical_task' AND call_count<>0"));
 }
}
