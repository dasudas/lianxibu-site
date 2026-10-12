package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.jobs.ProviderCallTelemetry;
import com.lianxibu.site.domain.jobs.ProviderCallTelemetry.Evidence;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
/** Complete bounded native call scans in an owned read-only snapshot; no current body or request output. */
public final class JdbcProviderCallTelemetry implements ProviderCallTelemetry.Api {
 private record Unknown(String id,Instant released){}
 private record Blocked(String id,String attempt){}
 private final JdbcTemplate jdbc;private final JdbcWorkerRecovery proofs;private final PlatformTransactionManager tx;private final Clock clock;
 public JdbcProviderCallTelemetry(DataSource source,PlatformTransactionManager tx,Clock clock){this.jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.proofs=new JdbcWorkerRecovery(source,new JdbcWorkerState(source));this.tx=Objects.requireNonNull(tx);this.clock=Objects.requireNonNull(clock);}
 @Override public Evidence read(){
  if(TransactionSynchronizationManager.isActualTransactionActive()||Thread.currentThread().isInterrupted())return null;
  try {
   var reader=new TransactionTemplate(tx);reader.setReadOnly(true);reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reader.setTimeout(10);
   return reader.execute(status->{
    var unknown=jdbc.query("SELECT id,slot_released_at FROM jobs WHERE record_kind='ai_attempt' AND state='unknown_external' ORDER BY slot_released_at,id LIMIT 201",(r,n)->new Unknown(r.getString(1),r.getTimestamp(2,Calendar.getInstance(TimeZone.getTimeZone("UTC")))==null?null:r.getTimestamp(2,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant()));
    var blocked=jdbc.query("SELECT r.id,a.id FROM jobs r LEFT JOIN jobs a ON a.root_job_id=r.id AND a.record_kind='ai_attempt' AND a.attempt_no=r.call_count WHERE r.record_kind='logical_task' AND r.state='blocked_configuration' ORDER BY r.id LIMIT 201",(r,n)->new Blocked(r.getString(1),r.getString(2)));
    if(unknown.size()>200||blocked.size()>200)throw unavailable();
    var seen=new HashSet<String>();Instant oldest=null;
    for(var row:unknown){
     var proof=proofs.attempt(row.id());
     if(!seen.add(row.id())||proof==null||!"unknown_external".equals(proof.attemptState())||proof.calls()<1||proof.calls()>3||row.released()==null||proof.started()==null||row.released().isBefore(proof.started()))throw unavailable();
     if(oldest==null||row.released().isBefore(oldest))oldest=row.released();
    }
    seen.clear();for(var row:blocked){
     if(!seen.add(row.id())||row.attempt()==null)throw unavailable();var proof=proofs.attempt(row.attempt());
     if(proof==null||!row.id().equals(proof.root())||!"blocked_configuration".equals(proof.rootState())||!"failed".equals(proof.attemptState())||proof.calls()<1||proof.calls()>3)throw unavailable();
     var count=jdbc.queryForObject("SELECT COUNT(*) FROM jobs r JOIN jobs a ON a.root_job_id=r.id WHERE r.id=? AND a.id=? AND r.error_code='provider_configuration' AND a.error_code='provider_configuration' AND a.release_reason='provider_failure' AND a.slot_released_at IS NOT NULL",Integer.class,row.id(),row.attempt());if(count==null||count!=1)throw unavailable();
    }
    Instant received=clock.instant().truncatedTo(ChronoUnit.MICROS);if(oldest!=null&&oldest.isAfter(received)||Thread.currentThread().isInterrupted())throw unavailable();
    return new Evidence(received,unknown.size(),oldest==null?0:Duration.between(oldest,received).getSeconds(),blocked.size());
   });
  }catch(RuntimeException unavailable){return null;}
 }
 private static IllegalStateException unavailable(){return new IllegalStateException("complete native provider call facts unavailable");}
}
