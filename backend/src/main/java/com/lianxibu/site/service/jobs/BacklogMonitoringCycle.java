package com.lianxibu.site.service.jobs;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** One native backlog read and the original permanent sample/history writer in the same transaction. */
public final class BacklogMonitoringCycle {
 public enum Outcome{SAMPLED,ALREADY_SAMPLED,STOPPED,BUSY}
 private final Key key;private final BacklogHistoryObservationService facts;private final OperationalMonitoringService.Store store;private final OperationalMonitoringService monitor;private final Clock clock;
 private final ThreadLocal<BacklogHistoryObservationService.Observation> captured=new ThreadLocal<>();private final AtomicBoolean active=new AtomicBoolean();private volatile boolean stopped=true;
 public BacklogMonitoringCycle(Key key,BacklogHistoryObservationService facts,OperationalMonitoringService.Store store,OperationalMonitoringService.ConfigurationSource configuration,SharedWriteExecutor writes,Clock clock){
  if(key==null||!"OPS-01".equals(key.ruleId())||facts==null||store==null||configuration==null||writes==null||clock==null)throw new IllegalArgumentException("native backlog monitoring dependencies required");this.key=key;this.facts=facts;this.store=store;this.clock=clock;
  var joined=new OperationalMonitoringService.Store(){
   public State committed(String action,String hash){return store.committed(action,hash);}public State read(Key key){return store.read(key);}
   public void save(State before,State next,List<Notice> notices,String action,String hash,String operator,String reason){store.save(before,next,notices,action,hash,operator,reason);var snapshot=captured.get();if(snapshot!=null)facts.save(snapshot);}
  };monitor=new OperationalMonitoringService(joined,configuration,writes,clock);
 }
 public void start(){stopped=false;}public void stop(){stopped=true;}
 public Outcome tick(){
  if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("backlog monitoring must acquire outside transactions");if(!active.compareAndSet(false,true))return Outcome.BUSY;
  try{
   Instant minute=clock.instant().truncatedTo(ChronoUnit.MINUTES);var previous=store.read(key);if(previous!=null&&previous.lastSampleAt()!=null){if(previous.lastSampleAt().isAfter(minute))throw new IllegalStateException("backlog monitoring clock rollback");if(previous.lastSampleAt().equals(minute))return Outcome.ALREADY_SAMPLED;}
   BacklogHistory reading=null;BacklogHistoryObservationService.Observation observation=null;
   try{var value=facts.acquire();Instant received=clock.instant().truncatedTo(ChronoUnit.MICROS);if(value!=null&&received.truncatedTo(ChronoUnit.MINUTES).equals(minute)&&!value.facts().readAt().isAfter(received)&&Duration.between(value.facts().readAt(),received).compareTo(Duration.ofSeconds(120))<=0){observation=value;reading=new BacklogHistory(value.facts());}}catch(RuntimeException unavailable){reading=null;observation=null;}
   if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;
   if(observation!=null)captured.set(observation);monitor.sample(key,reading!=null,reading,minute,action(minute));return Outcome.SAMPLED;
  }finally{captured.remove();active.set(false);}
 }
 private SharedWriteExecutor.ActionContext action(Instant minute){try{String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((key.component()+"\n"+key.resourceKey()).getBytes(StandardCharsets.UTF_8))).substring(0,32);return new SharedWriteExecutor.ActionContext("backlog-sample:"+id+":"+minute.getEpochSecond(),"ops_"+id,null);}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("backlog sample identity unavailable");}}
}
