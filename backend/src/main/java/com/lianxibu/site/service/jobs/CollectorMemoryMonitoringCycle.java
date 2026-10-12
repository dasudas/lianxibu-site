package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.jobs.CollectorMemoryFacts;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native facts outside writes; one durable original OPS06 sample per node and minute. */
public final class CollectorMemoryMonitoringCycle {
    public enum Outcome{SAMPLED,ALREADY_SAMPLED,STOPPED,BUSY}
    private final String node;private final Key key;private final CollectorMemoryFacts.Api facts;
    private final OperationalMonitoringService.Store store;private final OperationalMonitoringService monitor;private final Clock clock;
    private final AtomicBoolean active=new AtomicBoolean();private final Object lifecycle=new Object();private boolean stopped=true;private long generation;
    public CollectorMemoryMonitoringCycle(String node,CollectorMemoryFacts.Api facts,OperationalMonitoringService.Store store,OperationalMonitoringService monitor,Clock clock){
        if(node==null||facts==null||store==null||monitor==null||clock==null)throw new IllegalArgumentException("complete native collector memory monitor required");
        this.node=com.lianxibu.site.domain.protocol.WireValues.id(node,false);this.key=new Key("OPS-06","collector",node);this.facts=facts;this.store=store;this.monitor=monitor;this.clock=clock;
    }
    public void start(){synchronized(lifecycle){if(stopped){generation++;stopped=false;}}}
    public void stop(){synchronized(lifecycle){stopped=true;generation++;}}
    public Outcome tick(){
        long run;synchronized(lifecycle){if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;run=generation;}
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("collector memory acquisition must run outside transactions");
        if(!active.compareAndSet(false,true))return Outcome.BUSY;
        try{
            Instant minute=clock.instant().truncatedTo(ChronoUnit.MINUTES);var before=store.read(key);
            if(before!=null&&before.lastSampleAt()!=null){if(before.lastSampleAt().isAfter(minute))throw new IllegalStateException("collector memory monitoring clock rollback");if(before.lastSampleAt().equals(minute))return Outcome.ALREADY_SAMPLED;}
            CollectorMemory reading=null;
            try{var value=facts.read(node);Instant completed=clock.instant().truncatedTo(ChronoUnit.MICROS);
                if(value!=null&&node.equals(value.nodeId())&&completed.truncatedTo(ChronoUnit.MINUTES).equals(minute)&&!value.readAt().isAfter(completed)
                        &&Duration.between(value.readAt(),completed).compareTo(Duration.ofSeconds(120))<=0)reading=new CollectorMemory(value);
            }catch(RuntimeException unavailable){reading=null;}
            synchronized(lifecycle){if(stopped||generation!=run||Thread.currentThread().isInterrupted())return Outcome.STOPPED;
                monitor.sample(key,reading!=null,reading,minute,action(minute));}
            return Outcome.SAMPLED;
        }finally{active.set(false);}
    }
    private SharedWriteExecutor.ActionContext action(Instant minute){
        try{String id=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((node+"\n"+key.ruleId()+"\n"+key.component()).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,32);
            return new SharedWriteExecutor.ActionContext("collector-sample:"+id+":"+minute.getEpochSecond(),"ops_"+id,null);
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("collector sample identity unavailable");}
    }
}
