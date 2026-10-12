package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;import com.lianxibu.site.domain.jobs.BacklogHistoryTelemetry.*;import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
class BacklogHistoryWatermarkTest {
 static final Instant START=Instant.parse("2026-01-01T19:10:00Z");static final String EPOCH="00000000-0000-4000-8000-000000000001",OTHER="00000000-0000-4000-8000-000000000002";static final Key KEY=new Key("OPS-01","java","fictional-backlog");static final OperationalRuleEngine ENGINE=new OperationalRuleEngine();
 static Mutation mutation(int minute){return new Mutation(EPOCH,START.minusSeconds(60),START.minusSeconds(60),1);}
 static BacklogHistory value(int minute,Instant born,List<Span> spans){Instant read=START.plusSeconds(minute*60L);return new BacklogHistory(new Evidence(read,List.of(new Entry("fictional-root",1,"queued",born,read,spans)),mutation(minute)));}
 static BacklogHistory empty(int minute){return new BacklogHistory(new Evidence(START.plusSeconds(minute*60L),List.of(),mutation(minute)));}
 static State tick(State state,int minute,Reading reading){return ENGINE.sample(state,OperationalRuleEngineTest.config(1),1,START.plusSeconds(minute*60L),reading!=null,reading).state();}
 @Test void unknownHistoryStillTriggersActualWallAgeButCannotRecoverAndCodecRetainsAllProof(){
  State state=tick(State.empty(KEY),0,value(0,START.minusSeconds(7*86400L),null));assertEquals(Level.CRITICAL,state.level());assertEquals(Status.TELEMETRY_UNKNOWN,state.status());assertEquals("604800",state.observedValue());assertEquals(29,state.counters().size());assertEquals(state,OperationalStateCodec.decode(OperationalStateCodec.encode(state)));
  for(int m=1;m<=21;m++){state=tick(state,m,value(m,START.minusSeconds(60),null));assertEquals(Level.CRITICAL,state.level());assertFalse(state.recoveryReady());assertFalse(state.timers().containsKey("healthy"));}
 }
 @Test void onlyActualEmptyNativeQueueCompletesOriginalTwentyMinuteRecovery(){
  State state=tick(State.empty(KEY),0,value(0,START.minusSeconds(7*86400L),null));
  for(int m=1;m<=20;m++){state=tick(state,m,empty(m));assertEquals(Level.CRITICAL,state.level());}
  state=tick(state,21,empty(21));assertEquals(Status.RESOLVED,state.status());assertNull(state.level());
  var absent=new BacklogHistory(new Evidence(START.plusSeconds(22*60L),List.of()));assertEquals(Status.TELEMETRY_UNKNOWN,tick(state,22,absent).status());
 }
 @Test void partialProvenLowerBoundCanWarnAndCannotDisappearWhenHistoryBecomesUnavailable(){
  Instant born=START.minusSeconds(7200);State state=State.empty(KEY);
  for(int m=0;m<=10;m++){Instant end=START.plusSeconds(m*60L);state=tick(state,m,value(m,born,List.of(new Span(born,START,true,false),new Span(START,end,null,null))));}
  assertEquals(Level.WARNING,state.level());assertEquals(Status.TELEMETRY_UNKNOWN,state.status());assertEquals(7200,state.counters().get("backlog_eligible_seconds"));
  State missing=tick(state,11,value(11,born,null));assertEquals(7200,missing.counters().get("backlog_eligible_seconds"));assertEquals(Level.WARNING,missing.level());assertEquals(Status.TELEMETRY_UNKNOWN,missing.status());
  State actualDifferent=tick(missing,12,new BacklogHistory(new Evidence(START.plusSeconds(720),List.of(new Entry("fictional-new-root",1,"queued",START.plusSeconds(660),START.plusSeconds(720),List.of(new Span(START.plusSeconds(660),START.plusSeconds(720),true,false)))),mutation(12))));assertEquals(60,actualDifferent.counters().get("backlog_eligible_seconds"));assertEquals(Level.WARNING,actualDifferent.level());
 }
 @Test void originalPauseWindowClipAndUnavailableIntervalsNeverFabricateHealthyEligibility(){
  Instant born=START.minusSeconds(7200);var span=new Span(born,START,true,true);State paused=tick(State.empty(KEY),0,value(0,born,List.of(span)));assertEquals(0,paused.counters().get("backlog_eligible_seconds"));assertEquals(Status.NORMAL,paused.status());
  var unknown=new Span(born,START,true,null);assertEquals(Status.TELEMETRY_UNKNOWN,tick(State.empty(KEY),0,value(0,born,List.of(unknown))).status());
  var falseReady=new Span(born,START,false,null);assertEquals(Status.NORMAL,tick(State.empty(KEY),0,value(0,born,List.of(falseReady))).status());
  assertNotEquals(value(0,born,List.of(span)).toString(),value(0,born,List.of(unknown)).toString());assertEquals("BacklogHistoryEvidence[redacted]",value(0,born,List.of(span)).facts().toString());
 }
 @Test void NativeRollbackDamagedWatermarksSyntheticDowngradeAndFalseEpochRemainUnknown(){
  State state=tick(State.empty(KEY),0,value(0,START.minusSeconds(7*86400L),null));assertEquals(Status.TELEMETRY_UNKNOWN,tick(state,1,new Backlog(0,0,true)).status());assertEquals(Status.TELEMETRY_UNKNOWN,tick(state,1,value(0,START.minusSeconds(7*86400L),null)).status());
  var oldBirth=new BacklogHistory(new Evidence(START.plusSeconds(60),List.of(),new Mutation(OTHER,START.minusSeconds(60),START.minusSeconds(60),0)));assertEquals(Status.TELEMETRY_UNKNOWN,tick(state,1,oldBirth).status());
  var actualBirth=new BacklogHistory(new Evidence(START.plusSeconds(120),List.of(),new Mutation(OTHER,START.plusSeconds(60),START.plusSeconds(60),0)));State restart=tick(state,2,actualBirth);assertEquals(Level.CRITICAL,restart.level());assertEquals(START.plusSeconds(120),restart.timers().get("healthy"));
  var counters=new HashMap<>(state.counters());counters.remove("backlog_f7");State damaged=new State(state.key(),state.revision(),state.configurationRevision(),state.status(),state.level(),state.firstSeenAt(),state.lastSeenAt(),state.lastSampleAt(),state.observedValue(),state.threshold(),state.timers(),counters,state.guards(),state.acknowledged(),state.recoveryReady(),state.nextReminderAt(),state.silencedUntil());assertEquals(Status.TELEMETRY_UNKNOWN,tick(damaged,1,empty(1)).status());
  State unknown=tick(state,1,null);assertEquals(29,unknown.counters().size());assertEquals(Status.TELEMETRY_UNKNOWN,unknown.status());State config=ENGINE.sample(state,null,2,START.plusSeconds(60),true,empty(1)).state();assertEquals(Status.CONFIGURATION_UNKNOWN,config.status());assertEquals(29,config.counters().size());
 }
}
