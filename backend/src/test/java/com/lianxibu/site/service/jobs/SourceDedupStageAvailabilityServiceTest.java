package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupStageAvailabilityServiceTest {
 SourceDedupStageProofServiceTest f;JdbcSourceStageAvailability store;SourceDedupStageAvailabilityService service;
 SourceDedupStageProofServiceTest fixture(){return new SourceDedupStageProofServiceTest();}
 @BeforeEach void setup(){f=fixture();f.setup();installObserver();}
 void installObserver(){f.f.f.jdbc.execute(f.f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs ALTER COLUMN acted_at TIMESTAMP(6)":"ALTER TABLE jobs MODIFY acted_at TIMESTAMP(6) NULL");store=new JdbcSourceStageAvailability(f.f.f.source);service=observer(f.f.f.clock,f.f.f.writes);}
 SourceDedupStageAvailabilityService observer(Clock clock,SharedWriteExecutor writes){return new SourceDedupStageAvailabilityService(f.service,store,writes,new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.f.source),clock);}
 int observations(){return f.f.f.count("target_type='source_stage_availability'");}
 Instant cutoff(){return f.f.f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
 Instant at(SourceDedupStageResult result,Instant cutoff){return f.f.readonly(()->service.availableAt(result,cutoff));}
 @Test void everyOriginalPartMustCompleteBeforeObservingOneWholeResult(){
  assertNull(service.capture(f.ref));f.complete(0);assertNull(service.capture(f.ref));assertEquals(0,observations());f.complete(1);var result=f.read();assertNull(at(result,cutoff().plusSeconds(100)));
  Instant observed=service.capture(f.ref);assertEquals(cutoff().plusNanos(1000),observed);assertNull(at(result,cutoff()));assertEquals(observed,at(result,observed));assertEquals(1,observations());
  assertEquals(observed,observer(Clock.fixed(observed.plusSeconds(100),ZoneOffset.UTC),f.f.f.writes).capture(f.ref));assertEquals(1,observations());assertEquals(2,f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
  assertTrue(result.observationKeyJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=2048);assertFalse(result.observationKeyJson().contains("Fictional fragment summary"));
 }
 @Test void anEmptyCommittedWholePlanNeedsItsOwnObservationBeforeHistoricalAvailability(){
  f.install(0);installObserver();var result=f.read();assertTrue(result.members().isEmpty());assertNull(at(result,cutoff().plusSeconds(100)));Instant observed=service.capture(f.ref);assertEquals(observed,at(result,observed));assertNull(at(result,cutoff()));assertEquals(1,observations());assertEquals(0,f.f.f.count("record_kind='logical_task'"));
 }
 @Test void theClockIsOutsideOriginalReadAndCallingFromWriterIsRejected(){
  f.complete(0);f.complete(1);Clock guarded=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(0,observations());return cutoff().plusNanos(1);}};
  assertThrows(RuntimeException.class,()->f.f.tx(()->observer(guarded,f.f.f.writes).capture(f.ref)));assertEquals(0,observations());assertEquals(cutoff().plusNanos(1000),observer(guarded,f.f.f.writes).capture(f.ref));
 }
 @Test void damagedOriginalProofAndRehashedBadObservationNeverGrantAvailability(){
  f.complete(0);f.complete(1);service.capture(f.ref);var result=f.read();String id=f.f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='source_stage_availability'",String.class);String saved=f.f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);
  for(String mode:java.util.List.of("schema_version","proof_hash","observed_at","extra")){
   var p=(ObjectNode)CanonicalJson.parse(saved);if(mode.equals("extra"))p.put(mode,true);else p.put(mode,mode.equals("observed_at")?"2026-01-01T21:00:00.123456789Z":"fictional-wrong");String raw=JdbcFrozenInputs.canonical(p);f.f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);assertThrows(RuntimeException.class,()->at(result,cutoff().plusSeconds(100)));
  }
  f.f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",saved,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(saved))),id);
  f.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],f.f.receipt());assertThrows(RuntimeException.class,()->service.capture(f.ref));assertEquals(1,observations());
 }
 @Test void appendRollbackAndCommitAckLossLeaveOneFirstObservationWithNoExtraCalls(){
  f.complete(0);f.complete(1);f.f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_stage_observer_failure CHECK(target_type IS NULL OR target_type<>'source_stage_availability')");assertThrows(RuntimeException.class,()->service.capture(f.ref));assertEquals(0,observations());
  f.f.f.jdbc.execute(f.f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_stage_observer_failure":"ALTER TABLE jobs DROP CHECK fictional_stage_observer_failure");
  var fault=new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.f.source),fault,()->25,n->{});
  Instant observed=observer(f.f.f.clock,writes).capture(f.ref);assertEquals(observed,service.capture(f.ref));assertEquals(1,observations());assertEquals(2,f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void observationRolesBoundedBodiesAndCutoffPrecisionRemainStrict(){
  f.complete(0);f.complete(1);service.capture(f.ref);var result=f.read();String id=f.f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='source_stage_availability'",String.class);
  f.f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-other' WHERE id=?",id);assertThrows(RuntimeException.class,()->at(result,cutoff().plusSeconds(100)));f.f.f.jdbc.update("UPDATE jobs SET operator_id='source-stage-observer' WHERE id=?",id);
  String huge="{\"fictional\":\""+"F".repeat(2049)+"\"}";f.f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",huge,JdbcGenerationLineage.hash(huge),id);assertThrows(RuntimeException.class,()->at(result,cutoff().plusSeconds(100)));
  assertThrows(RuntimeException.class,()->service.availableAt(result,cutoff()));assertThrows(RuntimeException.class,()->at(result,cutoff().plusNanos(1)));
 }
 @ParameterizedTest @ValueSource(strings={"Asia/Tokyo","America/Los_Angeles"})
 void actualFirstObservationStoresUtcAndReplaysAcrossJvmTimezone(String zone){
  f.install(0);installObserver();var result=f.read();var prior=java.util.TimeZone.getDefault();
  try{
   java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));Instant first=service.capture(f.ref);
   var jdbc=f.f.f.jdbc;
   for(String field:java.util.List.of("acted_at","eligible_at","created_at","updated_at")){
    Instant physical=jdbc.query("SELECT "+field+" FROM jobs WHERE target_type='source_stage_availability'",(rs,n)->rs.getTimestamp(1,java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))).toInstant()).getFirst();
    assertEquals(first,physical,field);
   }
   java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));assertEquals(first,at(result,first));
   assertEquals(first,observer(Clock.fixed(first.plusSeconds(60),ZoneOffset.UTC),f.f.f.writes).capture(f.ref));assertEquals(1,observations());
  }finally{java.util.TimeZone.setDefault(prior);}
 }
}
