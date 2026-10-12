package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.jobs.ProviderReadinessTelemetry;
import com.lianxibu.site.domain.jobs.ProviderCallTelemetry;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;

import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Complete SQL and two bounded read-only account GETs before the permanent short writer. */
public final class ProviderMonitoringCycle {
 public enum Outcome {SAMPLED,ALREADY_SAMPLED,STOPPED,BUSY}
 private final Key key;private final ProviderReadinessTelemetry.Api account;private final ProviderCallTelemetry.Api calls;private final ProviderEvidence validation;
 private final OperationalMonitoringService.Store store;private final OperationalMonitoringService monitor;private final OperationalMonitoringService.ConfigurationSource configuration;private final Clock clock;
 private final AtomicBoolean active=new AtomicBoolean();private volatile boolean stopped=true;
 public ProviderMonitoringCycle(Key key,ProviderReadinessTelemetry.Api account,ProviderCallTelemetry.Api calls,ProviderEvidence validation,OperationalMonitoringService.Store store,OperationalMonitoringService monitor,OperationalMonitoringService.ConfigurationSource configuration,Clock clock){
  if(key==null||!"OPS-08".equals(key.ruleId())||account==null||calls==null||validation==null||store==null||monitor==null||configuration==null||clock==null)throw new IllegalArgumentException("controlled provider monitoring dependencies required");
  this.key=key;this.account=account;this.calls=calls;this.validation=validation;this.store=store;this.monitor=monitor;this.configuration=configuration;this.clock=clock;
 }
 public void start(){stopped=false;}public void stop(){stopped=true;}
 public Outcome tick(){
  if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("provider monitoring must acquire outside transactions");
  if(!active.compareAndSet(false,true))return Outcome.BUSY;
  try {
   Instant minute=clock.instant().truncatedTo(ChronoUnit.MINUTES);var previous=store.read(key);
   if(previous!=null&&previous.lastSampleAt()!=null){if(previous.lastSampleAt().isAfter(minute))throw new IllegalStateException("provider sample clock rollback");if(previous.lastSampleAt().equals(minute))return Outcome.ALREADY_SAMPLED;}
   OperationalRuleEngine.Provider reading=null;
   try {
    var nativeCalls=calls.read();var readiness=nativeCalls==null?null:account.read();
    Instant received=clock.instant().truncatedTo(ChronoUnit.MICROS);if(received.truncatedTo(ChronoUnit.MINUTES).equals(minute))reading=validation.validate(readiness,nativeCalls,received);
   }catch(RuntimeException unavailable){reading=null;}
   if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;
   monitor.sample(key,reading!=null,reading,minute,action(minute));return Outcome.SAMPLED;
  }finally{active.set(false);}
 }
 private SharedWriteExecutor.ActionContext action(Instant minute){
  try {String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((key.component()+"\n"+key.resourceKey()).getBytes(StandardCharsets.UTF_8))).substring(0,32);return new SharedWriteExecutor.ActionContext("provider-sample:"+id+":"+minute.getEpochSecond(),"ops_"+id,null);}
  catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("provider sample identity unavailable");}
 }
}
