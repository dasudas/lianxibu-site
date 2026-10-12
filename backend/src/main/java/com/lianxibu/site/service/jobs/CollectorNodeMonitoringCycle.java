package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.jobs.CollectorNodeFacts;import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** One native read per node/minute, separate permanent OPS02/05 samples; old receipts are never sampled twice. */
public final class CollectorNodeMonitoringCycle {
 public enum Outcome{SAMPLED,ALREADY_SAMPLED,STOPPED,BUSY}
 private final String node;private final List<Key> keys;private final CollectorNodeFacts.Api facts;private final OperationalMonitoringService.Store store;private final OperationalMonitoringService monitor;private final Clock clock;private final AtomicBoolean active=new AtomicBoolean();private volatile boolean stopped=true;
 public CollectorNodeMonitoringCycle(String node,List<Key> keys,CollectorNodeFacts.Api facts,OperationalMonitoringService.Store store,OperationalMonitoringService monitor,Clock clock){if(node==null||keys==null||keys.size()!=2||facts==null||store==null||monitor==null||clock==null)throw new IllegalArgumentException("complete native collector monitors required");var ids=new HashSet<String>();for(var key:keys)if(key==null||!node.equals(key.resourceKey())||!Set.of("OPS-02","OPS-05").contains(key.ruleId())||!ids.add(key.ruleId()))throw new IllegalArgumentException("complete native collector monitors required");this.node=node;this.keys=List.copyOf(keys);this.facts=facts;this.store=store;this.monitor=monitor;this.clock=clock;}
 public void start(){stopped=false;}public void stop(){stopped=true;}
 public Outcome tick(){
  if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("collector monitoring must acquire outside transactions");if(!active.compareAndSet(false,true))return Outcome.BUSY;
  try{Instant minute=clock.instant().truncatedTo(ChronoUnit.MINUTES);var pending=new ArrayList<Key>();for(var key:keys){var before=store.read(key);if(before!=null&&before.lastSampleAt()!=null){if(before.lastSampleAt().isAfter(minute))throw new IllegalStateException("collector monitoring clock rollback");if(before.lastSampleAt().equals(minute))continue;}pending.add(key);}if(pending.isEmpty())return Outcome.ALREADY_SAMPLED;
   CollectorNode reading=null;try{var value=facts.read(node);Instant completed=clock.instant().truncatedTo(ChronoUnit.MICROS);if(value!=null&&node.equals(value.nodeId())&&completed.truncatedTo(ChronoUnit.MINUTES).equals(minute)&&!value.readAt().isAfter(completed)&&Duration.between(value.readAt(),completed).compareTo(Duration.ofSeconds(120))<=0)reading=new CollectorNode(value);}catch(RuntimeException unavailable){reading=null;}
   RuntimeException failed=null;for(var key:pending){if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;try{monitor.sample(key,reading!=null,reading,minute,action(key,minute));}catch(RuntimeException unavailable){if(failed==null)failed=unavailable;}}
   if(failed!=null)throw new IllegalStateException("collector monitoring sample unavailable",failed);return Outcome.SAMPLED;
  }finally{active.set(false);}
 }
 private SharedWriteExecutor.ActionContext action(Key key,Instant minute){try{String id=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((node+"\n"+key.ruleId()+"\n"+key.component()).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,32);return new SharedWriteExecutor.ActionContext("collector-sample:"+id+":"+minute.getEpochSecond(),"ops_"+id,null);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("collector sample identity unavailable");}}
}
