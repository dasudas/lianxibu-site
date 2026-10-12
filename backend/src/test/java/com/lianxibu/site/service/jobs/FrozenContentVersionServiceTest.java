package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;

class FrozenContentVersionServiceTest {
 ResultJdbcStoreTest fixture;FrozenContentVersionService versions;ContentVersionKey version;
 @BeforeEach void setup(){fixture=new ResultJdbcStoreTest();fixture.fixture();install();}
 void install(){fixture.apply();versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(fixture.source));version=ContentVersionKey.fromJson(new JdbcResultState(fixture.source).receipt("fictional-apply","fictional-owner",ResultJdbcStoreTest.ROOT,fixture.identity.attemptId()).get("content_version"));}
 @Test void replacementOfCurrentRowDoesNotEraseOriginalFullBodyAndFrozenReferences(){var old=versions.find(version).orElseThrow();fixture.jdbc.update("UPDATE contents SET revision=8,body_revision=7,body_json=?,body_hash_v1=?,input_job_id='fictional-new-input'",ResultJdbcStoreTest.BODY.replace("Fictional text","Fictional newer body"),new byte[32]);var retained=versions.find(version).orElseThrow();assertEquals(old.version(),retained.version());assertEquals(old.inputHash(),retained.inputHash());assertEquals(old.bodyJson(),retained.bodyJson());assertEquals(old.context().json(),retained.context().json());assertTrue(retained.bodyJson().contains("Fictional text"));assertFalse(retained.bodyJson().contains("newer body"));assertEquals("fictional-citation",retained.context().referenceTargets().getFirst().path("id").asText());assertEquals(1,fixture.sends);}
 @Test void partialIdentityCannotSelectOtherKindRevisionHashOrInputOwner(){for(var candidate:new ContentVersionKey[]{new ContentVersionKey("agent",version.contentId(),version.revision(),version.bodyHash(),version.inputJobId()),new ContentVersionKey(version.contentKind(),"fictional-other",version.revision(),version.bodyHash(),version.inputJobId()),new ContentVersionKey(version.contentKind(),version.contentId(),9,version.bodyHash(),version.inputJobId()),new ContentVersionKey(version.contentKind(),version.contentId(),version.revision(),"f".repeat(64),version.inputJobId()),new ContentVersionKey(version.contentKind(),version.contentId(),version.revision(),version.bodyHash(),"fictional-other-root")})assertTrue(versions.find(candidate).isEmpty());}
 @Test void alteredReceiptOrMissingFrozenPartCannotBecomeHistoricalProof(){fixture.jdbc.update("UPDATE jobs SET action_payload=? WHERE target_type='result_application'","{}");assertThrows(IllegalStateException.class,()->versions.find(version));}
 @Test void missingInputOrRetainedBodyFailsClosedEvenWhenCurrentBodyStillMatches(){fixture.jdbc.update("DELETE FROM jobs WHERE purpose='input' AND part_no=0");assertThrows(IllegalStateException.class,()->versions.find(version));assertEquals(1,fixture.count("contents"));}
 @Test void originalSnapshotRemainsHashBoundAfterCurrentContentChanges(){
  var old=versions.find(version).orElseThrow();
  assertEquals(old.inputHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,CanonicalJson.parse(old.snapshotJson())));
  assertEquals(old.context().json(),JdbcFrozenInputs.canonical(CanonicalJson.parse(old.snapshotJson()).get(FrozenApplicationContext.FIELD)));
  fixture.jdbc.update("UPDATE contents SET input_job_id='fictional-later-owner',body_json='{}'");
  assertEquals(old.snapshotJson(),versions.find(version).orElseThrow().snapshotJson());
  fixture.jdbc.update("UPDATE jobs SET payload_json='{}' WHERE record_kind='snapshot_part' AND purpose='input'");
  assertThrows(IllegalStateException.class,()->versions.find(version));
 }
 @Test void resultVersionRequiresPermanentAvailabilityAtOrBeforeCutoff(){
  var time=fixture.clock.instant();assertTrue(versions.findAt(version,time).isEmpty());
  observe(time);
  assertTrue(versions.findAt(version,time.minusNanos(1000)).isEmpty());
  assertEquals(time,versions.findAt(version,time).orElseThrow().availableAt());
  assertTrue(versions.findAt(version,time.plusSeconds(1)).isPresent());
  fixture.jdbc.update("UPDATE jobs SET acted_at=? WHERE target_type='result_application'",java.sql.Timestamp.from(time.plusSeconds(2)));
  assertEquals(time,versions.findAt(version,time).orElseThrow().availableAt());
 }
 @Test void archiveAvailabilityUsesItsPermanentReceiptAndBodyPartTimes(){
  var proof=versions.find(version).orElseThrow();var archived=fixture.clock.instant().plusSeconds(10);
  fixture.writes.execute(fixture.action("fictional-timed-archive"),k->null,a->{new JdbcContentReviewEvidence(fixture.source).archive(version,proof.bodyJson(),proof.inputHash(),"fictional-owner",archived);return true;});
  observe(archived);
  assertTrue(versions.findAt(version,archived.minusNanos(1000)).isEmpty());assertEquals(archived,versions.findAt(version,archived).orElseThrow().availableAt());
  fixture.jdbc.update("UPDATE jobs SET created_at=? WHERE purpose='review'",java.sql.Timestamp.from(archived.plusSeconds(1)));
  assertEquals(archived,versions.findAt(version,archived).orElseThrow().availableAt());
 }
 java.time.Instant observe(java.time.Instant now){return new VersionAvailabilityService(versions,new JdbcVersionAvailability(fixture.source),fixture.writes,fixture.tx,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC)).capture(version);}
 @Test void proofReadHasExplicitCommittedIsolationAndCompletesBeforeClockObservation(){
  var checked=org.mockito.Mockito.spy(versions);
  org.mockito.Mockito.doAnswer(invocation->{
   assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());
   assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED,org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
   return invocation.callRealMethod();
  }).when(checked).find(version);
  var clock=org.mockito.Mockito.mock(java.time.Clock.class);
  org.mockito.Mockito.when(clock.instant()).thenAnswer(invocation->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return fixture.clock.instant();});
  assertEquals(fixture.clock.instant(),new VersionAvailabilityService(checked,new JdbcVersionAvailability(fixture.source),fixture.writes,fixture.tx,clock).capture(version));
  org.mockito.Mockito.verify(checked).find(version);
 }
 @Test void lateObservationCannotBackdateAndSubMicrosecondRoundsUp(){
  var now=fixture.clock.instant();assertNull(versions.find(version).orElseThrow().availableAt());
  assertThrows(IllegalStateException.class,()->fixture.writes.execute(fixture.action("fictional-uncommitted-observation"),k->null,a->observe(now)));
  assertEquals(now.plusNanos(1000),observe(now.plusNanos(1)));
  assertTrue(versions.findAt(version,now).isEmpty());
  assertEquals(now.plusNanos(1000),observe(now.plusSeconds(100)));
  assertEquals(now.plusNanos(1000),versions.findAt(version,now.plusNanos(1000)).orElseThrow().availableAt());
 }
 @Test void failedObservationKeepsOriginalContentButNeverCreatesCutoffProof(){
  fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_availability_failure CHECK(target_type IS NULL OR target_type<>'content_availability')");
  assertThrows(RuntimeException.class,()->observe(fixture.clock.instant()));
  assertTrue(versions.find(version).isPresent());assertTrue(versions.findAt(version,fixture.clock.instant().plusSeconds(1)).isEmpty());
 }
 @Test void corruptAvailabilityCannotBecomeHistoricalProof(){
  observe(fixture.clock.instant());fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='content_availability'",new byte[32]);
  assertThrows(RuntimeException.class,()->versions.findAt(version,fixture.clock.instant()));
 }
 @Test void nativeFirstRoleIdentityAndUnusedFieldsCannotSupplyAFirstTime(){
  for(String field:java.util.List.of("state='failed'","revision=1","operator_id='fictional-other'","reason='fictional-other'","record_identity_hash=UNHEX('"+"0".repeat(64)+"')","root_job_id='fictional-alien'","id='"+"d".repeat(64)+"'")){
   setup();observe(fixture.clock.instant());fixture.jdbc.update("UPDATE jobs SET "+field+" WHERE target_type='content_availability'");assertThrows(RuntimeException.class,()->versions.findAt(version,fixture.clock.instant()),field);
  }
 }
 @Test void firstPayloadTimeCannotBeRehashedAwayFromNativeUtcCommitTimes(){
  for(String field:java.util.List.of("acted_at","created_at","updated_at","eligible_at")){
   setup();observe(fixture.clock.instant());fixture.jdbc.update("UPDATE jobs SET "+field+"=? WHERE target_type='content_availability'",java.time.LocalDateTime.ofInstant(fixture.clock.instant().plusSeconds(1),java.time.ZoneOffset.UTC));assertThrows(RuntimeException.class,()->versions.findAt(version,fixture.clock.instant()),field);
  }
  setup();observe(fixture.clock.instant());var payload=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(fixture.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='content_availability'",String.class));payload.put("observed_at",fixture.clock.instant().minusSeconds(1).toString());String raw=JdbcFrozenInputs.canonical(payload);fixture.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='content_availability'",raw,JdbcGenerationLineage.hash(raw));assertThrows(RuntimeException.class,()->versions.findAt(version,fixture.clock.instant()));
 }
 @Test void firstReceiptHasPhysicalCapacityAndNativeUtcReadIsDefaultZoneIndependent(){
  observe(fixture.clock.instant());var original=java.util.TimeZone.getDefault();try{for(String zone:java.util.List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/Los_Angeles")){java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));assertEquals(fixture.clock.instant(),new JdbcVersionAvailability(fixture.source).read(version,fixture.store.input().inputHash()),zone);}}finally{java.util.TimeZone.setDefault(original);}
  fixture.jdbc.update("UPDATE jobs SET action_payload=? WHERE target_type='content_availability'","{\"fictional\":\""+"F".repeat(5000)+"\"}");assertThrows(RuntimeException.class,()->versions.findAt(version,fixture.clock.instant()));
  var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);new JdbcVersionAvailability(source);org.mockito.Mockito.verifyNoInteractions(source);
 }
}
