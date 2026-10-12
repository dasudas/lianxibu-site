package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupAvailabilityServiceTest {
 SourceDedupResultProofServiceTest f;JdbcSourceFragmentAvailability store;SourceDedupAvailabilityService service;
 SourceDedupResultProofServiceTest fixture(){return new SourceDedupResultProofServiceTest();}
 @BeforeEach void setup(){f=fixture();f.setup();f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs ALTER COLUMN acted_at TIMESTAMP(6)":"ALTER TABLE jobs MODIFY acted_at TIMESTAMP(6) NULL");store=new JdbcSourceFragmentAvailability(f.f.source);service=observer(f.f.clock,f.f.writes);}
 SourceDedupAvailabilityService observer(Clock clock,SharedWriteExecutor writes){return new SourceDedupAvailabilityService(f.service,store,writes,new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.source),clock);}
 String root(){return f.chunk.rootId();}
 int observations(){return f.f.count("target_type='source_fragment_availability'");}
 Instant oldCutoff(){return f.f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
 Instant availableAt(SourceDedupFragmentProof proof,Instant cutoff){return f.readonly(()->service.availableAt(proof,cutoff));}
 @Test void pendingNeverWritesAndOnlyTheCommittedReadUpperBoundQualifiesAtCutoff(){
  int count=f.f.count("1=1");assertNull(service.capture(root()));assertEquals(count,f.f.count("1=1"));f.complete();var proof=f.read();
  assertNull(availableAt(proof,oldCutoff().plusSeconds(100)));Instant observed=service.capture(root());assertEquals(Instant.parse("2026-01-01T21:00:00.123457Z"),observed);
  assertNull(availableAt(proof,oldCutoff()));assertEquals(observed,availableAt(proof,observed));assertEquals(1,observations());
  assertEquals(observed,observer(Clock.fixed(observed.plusSeconds(100),ZoneOffset.UTC),f.f.writes).capture(root()));assertEquals(1,observations());assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void theObservationClockRunsAfterTheReadTransactionAndNestedWritersAreRejected(){
  f.complete();Clock guarded=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(0,observations());return oldCutoff().plusNanos(1);}};
  assertThrows(RuntimeException.class,()->f.tx(()->observer(guarded,f.f.writes).capture(root())));assertEquals(0,observations());assertEquals(oldCutoff().plusNanos(1000),observer(guarded,f.f.writes).capture(root()));
 }
 @Test void damagedOriginalProofCannotCreateOrReplayAnObservation(){
  f.complete();f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],f.receipt());assertThrows(RuntimeException.class,()->service.capture(root()));assertEquals(0,observations());
  String handoff=JdbcFrozenInputs.canonical(f.pure.handoff(f.plan,f.pure.universe,f.chunk));f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",JdbcGenerationLineage.hash(handoff),f.receipt());service.capture(root());
  f.f.jdbc.update("DELETE FROM jobs WHERE id=?",f.part());assertThrows(RuntimeException.class,()->service.capture(root()));assertEquals(1,observations());
 }
 @Test void rehashedObservationFieldsAndDifferentOriginalProofCannotGrantAvailability(){
  f.complete();service.capture(root());var proof=f.read();String id=f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='source_fragment_availability'",String.class);String original=f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);
  for(String mode:List.of("schema_version","proof_hash","observed_at","extra")){
   var p=(ObjectNode)CanonicalJson.parse(original);if(mode.equals("extra"))p.put("extra",true);else p.put(mode,mode.equals("observed_at")?"2026-01-01T21:00:00.123456789Z":"fictional-wrong");String raw=JdbcFrozenInputs.canonical(p);
   f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);assertThrows(RuntimeException.class,()->availableAt(proof,oldCutoff().plusSeconds(100)));
  }
  f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",original,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(original))),id);
  var other=f.pure.handoff(f.plan,f.pure.universe,f.chunk);((ObjectNode)other.get("payload")).put("summary","Fictional other summary");String envelope=ResultApplicationService.providerEnvelope(f.pure.input(f.chunk),JdbcFrozenInputs.canonical(other.get("payload")));other.put("result_hash",ResultApplicationService.validate(f.pure.input(f.chunk),envelope).resultHash());
  var altered=SourceDedupFragmentProof.read(f.plan,f.pure.universe,root(),envelope,JdbcFrozenInputs.canonical(other));assertNull(availableAt(altered,oldCutoff().plusSeconds(100)));assertEquals(1,observations());
 }
 @Test void failedAppendRollsBackAndLostCommitAcknowledgementRecoversOneOriginalReceipt(){
  f.complete();f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_fragment_observer_failure CHECK(target_type IS NULL OR target_type<>'source_fragment_availability')");assertThrows(RuntimeException.class,()->service.capture(root()));assertEquals(0,observations());
  f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_fragment_observer_failure":"ALTER TABLE jobs DROP CHECK fictional_fragment_observer_failure");
  var fault=new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional ACK lost");}}};
  var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});Instant observed=observer(f.f.clock,writes).capture(root());assertEquals(observed,service.capture(root()));assertEquals(1,observations());assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void permanentObservationIdentityRolesAndBoundedBodiesAreRequired(){
  f.complete();service.capture(root());var proof=f.read();String id=f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='source_fragment_availability'",String.class);
  f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-other' WHERE id=?",id);assertThrows(RuntimeException.class,()->availableAt(proof,oldCutoff().plusSeconds(100)));f.f.jdbc.update("UPDATE jobs SET operator_id='fragment-observer' WHERE id=?",id);
  String huge="{\"fictional\":\""+"F".repeat(2049)+"\"}";f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",huge,JdbcGenerationLineage.hash(huge),id);assertThrows(RuntimeException.class,()->availableAt(proof,oldCutoff().plusSeconds(100)));
  assertThrows(RuntimeException.class,()->service.availableAt(proof,oldCutoff()));assertThrows(RuntimeException.class,()->availableAt(proof,oldCutoff().plusNanos(1)));
 }
}
