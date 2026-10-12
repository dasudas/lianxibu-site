package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.jobs.ApiRuntimeTelemetry;import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Acquire the original complete native process window outside the permanent minute sample writer. */
public final class ApiMonitoringCycle {
 public enum Outcome{SAMPLED,ALREADY_SAMPLED,STOPPED,BUSY}
 private final Key key;private final ApiRuntimeTelemetry.Api facts;private final OperationalMonitoringService.Store store;private final OperationalMonitoringService monitor;private final Clock clock;
 private final AtomicBoolean active=new AtomicBoolean();private volatile boolean stopped=true;
 public ApiMonitoringCycle(Key key,ApiRuntimeTelemetry.Api facts,OperationalMonitoringService.Store store,OperationalMonitoringService monitor,Clock clock){if(key==null||!"OPS-09".equals(key.ruleId())||facts==null||store==null||monitor==null||clock==null)throw new IllegalArgumentException("native API monitoring dependencies required");this.key=key;this.facts=facts;this.store=store;this.monitor=monitor;this.clock=clock;}
 public void start(){stopped=false;}public void stop(){stopped=true;}
 public Outcome tick(){
  if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("API monitoring must acquire outside transactions");if(!active.compareAndSet(false,true))return Outcome.BUSY;
  try{Instant minute=clock.instant().truncatedTo(ChronoUnit.MINUTES);var previous=store.read(key);if(previous!=null&&previous.lastSampleAt()!=null){if(previous.lastSampleAt().isAfter(minute))throw new IllegalStateException("API monitoring clock rollback");if(previous.lastSampleAt().equals(minute))return Outcome.ALREADY_SAMPLED;}
   ApiWindow reading=null;try{var value=facts.read();Instant received=clock.instant().truncatedTo(ChronoUnit.MICROS);if(value!=null&&received.truncatedTo(ChronoUnit.MINUTES).equals(minute)&&!value.readAt().isAfter(received)&&Duration.between(value.readAt(),received).compareTo(Duration.ofSeconds(120))<=0)reading=new ApiWindow(value);}catch(RuntimeException unavailable){reading=null;}
   if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;monitor.sample(key,reading!=null,reading,minute,action(minute));return Outcome.SAMPLED;
  }finally{active.set(false);}
 }
 private SharedWriteExecutor.ActionContext action(Instant minute){try{String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((key.component()+"\n"+key.resourceKey()).getBytes(StandardCharsets.UTF_8))).substring(0,32);return new SharedWriteExecutor.ActionContext("api-sample:"+id+":"+minute.getEpochSecond(),"ops_"+id,null);}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("API sample identity unavailable");}}
}
