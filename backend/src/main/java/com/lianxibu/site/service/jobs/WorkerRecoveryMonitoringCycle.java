package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.jobs.WorkerRecoveryTelemetry;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.settings.OperationalRulePolicy;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Reads completed actual scans; it never invokes recovery on the monitoring thread. */
public final class WorkerRecoveryMonitoringCycle {
 public enum Outcome {SAMPLED,ALREADY_SAMPLED,STOPPED,BUSY}
 private final Key key;private final WorkerRecoveryTelemetry.Api facts;private final WorkerRecoveryEvidence validation;
 private final OperationalMonitoringService.Store store;private final OperationalMonitoringService monitor;private final OperationalMonitoringService.ConfigurationSource configuration;private final Clock clock;
 private final AtomicBoolean active=new AtomicBoolean();private volatile boolean stopped=true;
 public WorkerRecoveryMonitoringCycle(Key key,WorkerRecoveryTelemetry.Api facts,WorkerRecoveryEvidence validation,OperationalMonitoringService.Store store,OperationalMonitoringService monitor,OperationalMonitoringService.ConfigurationSource configuration,Clock clock){
  if(key==null||!"OPS-03".equals(key.ruleId())||facts==null||validation==null||store==null||monitor==null||configuration==null||clock==null)throw new IllegalArgumentException("controlled recovery monitoring dependencies required");
  this.key=key;this.facts=facts;this.validation=validation;this.store=store;this.monitor=monitor;this.configuration=configuration;this.clock=clock;
 }
 public void start(){stopped=false;}public void stop(){stopped=true;}
 public Outcome tick(){
  if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("recovery monitoring must acquire outside transactions");
  if(!active.compareAndSet(false,true))return Outcome.BUSY;
  try {
   Instant minute=clock.instant().truncatedTo(ChronoUnit.MINUTES);var previous=store.read(key);
   if(previous!=null&&previous.lastSampleAt()!=null){if(previous.lastSampleAt().isAfter(minute))throw new IllegalStateException("recovery sample clock rollback");if(previous.lastSampleAt().equals(minute))return Outcome.ALREADY_SAMPLED;}
   OperationalRuleEngine.SlotRecovery reading=null;
   try {
    var actual=facts.read();var config=configuration.current();long threshold=0;
    if(config!=null){var rules=config.rules().stream().filter(rule->"OPS-03".equals(rule.ruleId())).toList();if(rules.size()==1)threshold=OperationalRulePolicy.compile(rules.getFirst()).critical().longValueExact();}
    Instant received=clock.instant().truncatedTo(ChronoUnit.MICROS);if(received.truncatedTo(ChronoUnit.MINUTES).equals(minute))reading=validation.validate(actual,received,threshold);
   }catch(RuntimeException unavailable){reading=null;}
   if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;
   monitor.sample(key,reading!=null,reading,minute,action(minute));return Outcome.SAMPLED;
  }finally{active.set(false);}
 }
 private SharedWriteExecutor.ActionContext action(Instant minute){
  try {String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((key.component()+"\n"+key.resourceKey()).getBytes(StandardCharsets.UTF_8))).substring(0,32);return new SharedWriteExecutor.ActionContext("recovery-sample:"+id+":"+minute.getEpochSecond(),"ops_"+id,null);}
  catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("recovery sample identity unavailable");}
 }
}
