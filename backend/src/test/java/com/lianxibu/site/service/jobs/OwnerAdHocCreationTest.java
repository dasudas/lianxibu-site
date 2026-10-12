package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
/** Original native acceptance, owner admission and complete same-input budget identity. */
class OwnerAdHocCreationTest {
 GenerationJdbcStoreTest f;JdbcOwnerAdHocCreation accepted;OwnerAdHocCreateService create;
 boolean allowed=true;int freezes;final Actor owner=new Actor("fictional-owner","owner");
 GenerationJdbcStoreTest fixtureSource(){return new GenerationJdbcStoreTest();}
 @BeforeEach void setup(){
  f=fixtureSource();f.fixture();f.clock=Clock.fixed(Instant.parse("2026-01-02T02:00:00.123456Z"),ZoneOffset.UTC);
  f.jdbc.execute("ALTER TABLE worker_lock ADD(slot_state VARCHAR(16) DEFAULT 'free',slot_revision BIGINT DEFAULT 0,fencing_token BIGINT DEFAULT 0,job_id VARCHAR(64),holder VARCHAR(128),lease_until TIMESTAMP(6),occupied_since TIMESTAMP(6))");
  accepted=new JdbcOwnerAdHocCreation(f.source);create=new OwnerAdHocCreateService(f.writes,accepted,()->{freezes++;return new FrozenGenerationSettings(7,List.of("fictional-focus"),List.of());},()->allowed,f.clock);
 }
 JdbcOwnerAdHocCreation.Accepted first(){return create.create(owner,"fictional-question","Fictional original question","Fictional original reason","fictional-create");}
 @Test void NativeAcceptanceHasOriginalPromptAndPreferencesButNoRootAttemptOrSlotReservation(){
  var original=first();assertEquals("waiting_input",original.response().state());assertEquals("generation_intent",original.response().recordKind());assertEquals(0,original.response().callCount());assertNull(original.response().maxAttempts());assertEquals("free",original.response().slotState());
  assertEquals(1,f.count("record_kind='generation_intent'"));assertEquals(1,f.count("record_kind='disposition'"));assertEquals(0,f.count("record_kind='logical_task' OR record_kind='ai_attempt' OR record_kind='snapshot_part'"));assertEquals(1,freezes);
  assertEquals("Fictional original question",original.command().prompt());assertEquals(7,original.settings().revision());assertEquals(f.clock.instant(),original.cutoff());assertFalse(original.toString().contains(original.command().prompt()));
 }
 @Test void OwnerAuthorizationAndMalformedCommandsPrecedeAnyNativeReadOrPreferenceFreeze(){
  var forbidden=new org.springframework.jdbc.datasource.AbstractDataSource(){public java.sql.Connection getConnection(){throw new AssertionError("fictional unauthorized database access");}public java.sql.Connection getConnection(String user,String password){return getConnection();}};
  var denied=new OwnerAdHocCreateService(new SharedWriteExecutor(new JdbcSharedWriteEntry(forbidden),new DataSourceTransactionManager(forbidden),()->25,n->{}),new JdbcOwnerAdHocCreation(forbidden),()->{throw new AssertionError("fictional unauthorized settings access");},()->{throw new AssertionError("fictional unauthorized gate access");},f.clock);
  assertThrows(IdentityService.Rejected.class,()->denied.create(null,null,null,null,null));assertThrows(IdentityService.Rejected.class,()->denied.create(new Actor("fictional-member","member"),null,null,null,null));
  assertThrows(IllegalArgumentException.class,()->denied.create(owner,"fictional-question"," \n\t","Fictional reason","fictional-invalid"));assertThrows(IllegalArgumentException.class,()->denied.create(owner,"fictional-question","bad\ud800","Fictional reason","fictional-invalid"));assertEquals(0,freezes);assertEquals(0,f.count("1=1"));
 }
 @Test void ReplayUsesTheOriginalCompleteReceiptEvenAfterThePreparationGateCloses(){
  var original=first();allowed=false;assertEquals(original.json(),first().json());assertEquals(1,freezes);assertThrows(GenerationFlowService.CapacityGuarded.class,()->create.create(owner,"fictional-other","Fictional another prompt","Fictional reason","fictional-paused"));assertEquals(2,f.count("1=1"));
 }
 @Test void ANewTargetReasonActorOrKeyCannotResetTheSamePromptBudget(){
  var original=first();var changed=OwnerAdHocCreateCommand.create(new Actor("fictional-owner-two","owner"),"fictional-other",original.command().prompt(),"Fictional other reason");assertEquals(original.command().logicalKey(),changed.logicalKey());assertEquals(original.command().rootId(),changed.rootId());
  assertEquals("input_unchanged",assertThrows(JdbcOwnerAdHocCreation.Rejected.class,()->create.create(new Actor("fictional-owner-two","owner"),"fictional-other",original.command().prompt(),"Fictional other reason","fictional-other-key")).getMessage());assertEquals(2,f.count("1=1"));
 }
 @Test void ReusingTargetOrIdempotencyKeyWithDifferentFullInputFailsWithoutExtraIntent(){
  first();assertEquals("revision_conflict",assertThrows(JdbcOwnerAdHocCreation.Rejected.class,()->create.create(owner,"fictional-question","Fictional changed prompt","Fictional reason","fictional-reused-target")).getMessage());
  assertEquals("identity_conflict",assertThrows(JdbcOwnerAdHocCreation.Rejected.class,()->create.create(owner,"fictional-another","Fictional changed prompt","Fictional reason","fictional-create")).getMessage());assertEquals(2,f.count("1=1"));
  var changed=create.create(owner,"fictional-another","Fictional changed prompt","Fictional reason","fictional-new");assertNotEquals(first().command().logicalKey(),changed.command().logicalKey());assertEquals(4,f.count("1=1"));
 }
 @Test void ACorruptOriginalReceiptCannotBeHiddenByReplayOrDuplicateInput(){
  first();f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='owner_ad_hoc_create'",new byte[32]);assertThrows(IllegalStateException.class,this::first);assertThrows(IllegalStateException.class,()->create.create(owner,"fictional-other","Fictional original question","Fictional other reason","fictional-duplicate"));assertEquals(2,f.count("1=1"));
 }
 @Test void ReceiptFailureRollsBackTheNativeIntentAndDoesNotModifyTheSlot(){
  f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_acceptance_failure CHECK(record_kind<>'disposition')");assertThrows(RuntimeException.class,this::first);assertEquals(0,f.count("1=1"));assertEquals("free",f.jdbc.queryForObject("SELECT slot_state FROM worker_lock WHERE id=1",String.class));
 }
 @Test void OriginalAcceptanceUtcIsIndependentOfFourDefaultTimeZones(){
  var previous=java.util.TimeZone.getDefault();try{
   for(String zone:java.util.List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/Los_Angeles")){
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));String id="fictional-zone-"+zone.replace('/','-');
    var accepted=create.create(owner,id,"Fictional timezone question "+zone,"Fictional UTC reason",id);
    assertEquals(0,accepted.cutoff().getNano()%1000);assertEquals(accepted.json(),create.create(owner,id,"Fictional timezone question "+zone,"Fictional UTC reason",id).json());
   }
  }finally{java.util.TimeZone.setDefault(previous);}
 }
 @Test void FullUnicodeInputAndClosedOriginalCommandRemainBoundedAndIndependentOfAuditReason(){
  var command=OwnerAdHocCreateCommand.create(owner,"fictional-question","😀".repeat(32768),"Fictional reason");assertEquals(32768,command.prompt().codePointCount(0,command.prompt().length()));assertEquals(command.json(),OwnerAdHocCreateCommand.original(CanonicalJson.parse(command.json())).json());assertThrows(IllegalArgumentException.class,()->OwnerAdHocCreateCommand.create(owner,"fictional-question","x".repeat(32769),"Fictional reason"));
  var damaged=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(command.json());damaged.put("model","fictional-injected");assertThrows(IllegalArgumentException.class,()->OwnerAdHocCreateCommand.original(damaged));
  var receipt=create.create(owner,"fictional-question",command.prompt(),"Fictional reason","fictional-large");assertEquals(command.prompt(),receipt.command().prompt());assertTrue(receipt.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<524288);
 }
}
